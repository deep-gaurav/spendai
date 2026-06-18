package com.spendai.app.domain.agent

import android.util.Log
import androidx.room.withTransaction
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.local.entity.TransactionLink
import com.spendai.app.data.local.entity.TransactionStatus
import com.spendai.app.data.repository.TransactionRepository
import com.spendai.app.inference.GemmaInferenceEngine
import com.spendai.app.inference.InferenceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

/**
 * Agent 3: Transaction Auditor.
 *
 * Takes A2's candidate transaction, queries the 10 closest transactions (including their
 * raw SMS text) from the database, and asks the LLM to verify and commit:
 *  - Links transfers / refund transactions.
 *  - Deduplicates incoming duplicates (e.g. Swiggy confirmations vs bank debits).
 *  - Audits the current transaction for A1/A2 parsing or resolution mistakes and corrects them.
 *  - Can edit/correct previously committed transactions (e.g. correcting debit/credit direction
 *    of credit card payments, deleting incorrect duplicates, updating reference numbers).
 */
class Agent3Auditor(
    private val engine: GemmaInferenceEngine,
    private val database: AppDatabase,
    private val transactionRepository: TransactionRepository,
) {
    private companion object {
        const val TAG = "Agent3Auditor"
        const val LOG_TRUNCATE_CHARS = 400
        const val A3_MAX_OUTPUT_TOKENS = 65536
        fun truncate(s: String?): String {
            if (s == null) return "<null>"
            return if (s.length <= LOG_TRUNCATE_CHARS) s
            else s.take(LOG_TRUNCATE_CHARS) + "...[+${s.length - LOG_TRUNCATE_CHARS} chars]"
        }
    }

    suspend fun reviewAndCommit(
        candidate: Transaction,
        rawSmsId: Long,
        rawSmsText: String,
        a2Prompt: String,
        a2Response: String,
    ): A3Outcome = withContext(Dispatchers.IO) {
        require(engine.state.value is InferenceState.Ready) {
            "Engine not READY (state=${engine.state.value}). Call initialize() first."
        }

        val contextTransactions = loadContext(candidate.txnAtMillis)
        val account = database.accountDao().getById(candidate.accountId)
        val accountLabel = "${account?.issuer ?: "Unknown"} ${account?.maskedNumber ?: ""}".trim()
        val merchant = candidate.merchantId?.let { database.merchantDao().getById(it) }
        val merchantName = merchant?.name

        val candidateInfo = AgentPrompt.A3CandidateInfo(
            rawSmsText = rawSmsText,
            amountPaise = candidate.amountPaise,
            direction = candidate.direction,
            accountId = candidate.accountId,
            accountLabel = accountLabel,
            merchantName = merchantName,
            referenceNo = candidate.referenceNo,
            title = candidate.title
        )

        val userMessage = AgentPrompt.a3UserMessage(contextTransactions, candidateInfo)
        val fullPrompt = AgentPrompt.A3_SYSTEM_INSTRUCTION + "\n\n" + userMessage

        Log.d(TAG, "A3 input rawSmsId=$rawSmsId fullPrompt length: ${fullPrompt.length}")
        val firstText: String? = runCatching {
            engine.generatePredictionTracking(
                prompt = fullPrompt,
                stepLabel = "agent3.audit",
                maxOutputTokens = A3_MAX_OUTPUT_TOKENS,
            ).toList().joinToString("")
        }.onFailure { Log.w(TAG, "A3 first attempt failed: ${it.message}") }
            .getOrNull()
        Log.d(TAG, "A3 raw response: ${truncate(firstText)}")
        val firstParsed = firstText?.let { AgentJsonParse.tryParse(it, A3Contract.serializer()) }

        val (contract, responseText) = if (firstParsed != null) {
            firstParsed to firstText
        } else {
            val retryText: String? = runCatching {
                engine.generatePredictionTracking(
                    prompt = AgentPrompt.A3_CORRECTIVE_PROMPT,
                    stepLabel = "agent3.audit.retry",
                    maxOutputTokens = A3_MAX_OUTPUT_TOKENS,
                ).toList().joinToString("")
            }.onFailure { Log.w(TAG, "A3 retry failed: ${it.message}") }
                .getOrNull()
            val retryParsed = retryText?.let { AgentJsonParse.tryParse(it, A3Contract.serializer()) }
            if (retryParsed != null) {
                retryParsed to retryText
            } else {
                val partial = retryText ?: firstText
                throw A3FailureException(
                    prompt = fullPrompt,
                    response = partial,
                    cause = IllegalStateException(
                        "Agent 3 returned no parseable JSON for rawSmsId=$rawSmsId"
                    ),
                )
            }
        }

        Log.d(TAG, "A3 contract decision: ${contract.currentDecision.decision}")

        val now = System.currentTimeMillis()
        val committedTxnId = database.withTransaction {
            // 1. Apply modifications to previous transactions
            contract.modifications.forEach { mod ->
                val existing = database.transactionDao().getById(mod.transactionId)
                if (existing != null) {
                    if (mod.status == "DELETED") {
                        database.transactionDao().delete(existing)
                        Log.d(TAG, "A3 deleted previous transactionId=${mod.transactionId}")
                    } else {
                        var updated = existing
                        if (mod.direction != null) updated = updated.copy(direction = mod.direction)
                        if (mod.accountId != null) updated = updated.copy(accountId = mod.accountId)
                        if (mod.merchantId != null) updated = updated.copy(merchantId = mod.merchantId)
                        if (mod.categoryId != null) updated = updated.copy(categoryId = mod.categoryId)
                        if (mod.title != null) updated = updated.copy(title = mod.title)
                        if (mod.referenceNo != null) updated = updated.copy(referenceNo = mod.referenceNo)
                        if (updated != existing) {
                            database.transactionDao().update(updated)
                            Log.d(TAG, "A3 modified previous transactionId=${mod.transactionId}")
                        }

                        if (mod.transferLinkWithTransactionId != null) {
                            val linkType = mod.transferLinkType ?: "SELF_TRANSFER"
                            database.transactionLinkDao().insertIgnore(
                                TransactionLink(
                                    fromTransactionId = mod.transferLinkWithTransactionId,
                                    toTransactionId = mod.transactionId,
                                    linkType = linkType,
                                    confidence = 1.0f,
                                    createdAt = now,
                                )
                            )
                        }
                    }
                }
            }

            // 2. Commit current transaction decision
            val dec = contract.currentDecision
            when (dec.decision) {
                "DUPLICATE" -> {
                    val dupId = dec.duplicateOfTransactionId ?: -1L
                    if (dupId != -1L) {
                        val existing = database.transactionDao().getById(dupId)
                        if (existing != null) {
                            // Merge/update duplicate transaction
                            var updated = existing
                            val ref = dec.referenceNo ?: candidate.referenceNo
                            val chan = candidate.channel
                            val mId = dec.merchantId ?: candidate.merchantId
                            val cId = dec.categoryId ?: candidate.categoryId
                            val title = dec.title ?: candidate.title
                            if (existing.referenceNo.isNullOrEmpty() && !ref.isNullOrEmpty()) {
                                updated = updated.copy(referenceNo = ref)
                            }
                            if (existing.channel.isNullOrEmpty() && !chan.isNullOrEmpty()) {
                                updated = updated.copy(channel = chan)
                            }
                            if (existing.merchantId == null && mId != null) {
                                updated = updated.copy(merchantId = mId)
                            }
                            if (existing.categoryId == null && cId != null) {
                                updated = updated.copy(categoryId = cId)
                            }
                            if (existing.title.isNullOrEmpty() && !title.isNullOrEmpty()) {
                                updated = updated.copy(title = title)
                            }
                            if (updated != existing) {
                                database.transactionDao().update(updated)
                            }

                            if (dec.transferLinkWithTransactionId != null) {
                                val linkType = dec.transferLinkType ?: "SELF_TRANSFER"
                                database.transactionLinkDao().insertIgnore(
                                    TransactionLink(
                                        fromTransactionId = dec.transferLinkWithTransactionId,
                                        toTransactionId = dupId,
                                        linkType = linkType,
                                        confidence = candidate.confidence,
                                        createdAt = now,
                                    )
                                )
                            }
                        }
                    }
                    dupId
                }
                "IGNORE" -> {
                    -1L
                }
                else -> { // COMMIT
                    var finalTxn = candidate
                    if (dec.accountId != null) finalTxn = finalTxn.copy(accountId = dec.accountId)
                    if (dec.merchantId != null) finalTxn = finalTxn.copy(merchantId = dec.merchantId)
                    if (dec.categoryId != null) finalTxn = finalTxn.copy(categoryId = dec.categoryId)
                    if (dec.direction != null) finalTxn = finalTxn.copy(direction = dec.direction)
                    if (dec.amountPaise != null) finalTxn = finalTxn.copy(amountPaise = dec.amountPaise)
                    if (dec.title != null) finalTxn = finalTxn.copy(title = dec.title)
                    if (dec.referenceNo != null) finalTxn = finalTxn.copy(referenceNo = dec.referenceNo)

                    // Mark status to CONFIRMED
                    finalTxn = finalTxn.copy(
                        status = TransactionStatus.CONFIRMED.name,
                        createdAt = now
                    )

                    val newTxnId = database.transactionDao().insert(finalTxn)
                    if (dec.transferLinkWithTransactionId != null) {
                        val linkType = dec.transferLinkType ?: "SELF_TRANSFER"
                        database.transactionLinkDao().insertIgnore(
                            TransactionLink(
                                fromTransactionId = dec.transferLinkWithTransactionId,
                                toTransactionId = newTxnId,
                                linkType = linkType,
                                confidence = candidate.confidence,
                                createdAt = now,
                            )
                        )
                    }
                    newTxnId
                }
            }
        }

        A3Outcome(
            transactionId = committedTxnId,
            prompt = fullPrompt,
            response = responseText,
            isDuplicate = (contract.currentDecision.decision == "DUPLICATE"),
            isIgnored = (contract.currentDecision.decision == "IGNORE")
        )
    }

    private suspend fun loadContext(targetMillis: Long): List<AgentPrompt.A3ContextTransaction> {
        val startMillis = targetMillis - 7 * 24 * 60 * 60 * 1000L
        val endMillis = targetMillis + 7 * 24 * 60 * 60 * 1000L
        val list = transactionRepository.getTransactionsInRange(
            startMillis = startMillis,
            endMillis = endMillis,
            targetMillis = targetMillis
        ).take(20)

        return list.map { txn ->
            val rawSms = database.smsDao().getById(txn.rawSmsId)
            val smsText = rawSms?.msgBody ?: ""
            val account = database.accountDao().getById(txn.accountId)
            val accountLabel = "${account?.issuer ?: "Unknown"} ${account?.maskedNumber ?: ""}".trim()
            val merchant = txn.merchantId?.let { database.merchantDao().getById(it) }

            AgentPrompt.A3ContextTransaction(
                id = txn.id,
                rawSmsText = smsText,
                amountPaise = txn.amountPaise,
                direction = txn.direction,
                accountId = txn.accountId,
                accountLabel = accountLabel,
                merchantName = merchant?.name,
                referenceNo = txn.referenceNo,
                title = txn.title
            )
        }
    }
}
