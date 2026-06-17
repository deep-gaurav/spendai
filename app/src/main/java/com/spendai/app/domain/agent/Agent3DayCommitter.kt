package com.spendai.app.domain.agent

import android.util.Log
import com.spendai.app.data.local.entity.SourceStatus
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.local.entity.TransactionLink
import com.spendai.app.data.local.entity.TransactionStatus
import com.spendai.app.data.repository.FinancialSourceRepository
import com.spendai.app.data.repository.PendingReviewRepository
import com.spendai.app.data.repository.TransactionLinkRepository
import com.spendai.app.data.repository.TransactionRepository
import com.spendai.app.data.local.entity.PendingReview
import com.spendai.app.data.local.entity.PendingReviewKind
import com.spendai.app.data.local.entity.TransactionLinkType
import com.spendai.app.domain.model.Commit
import com.spendai.app.domain.model.CommitLink
import com.spendai.app.domain.model.Confidence
import com.spendai.app.inference.GemmaInferenceEngine
import com.spendai.app.inference.InferenceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/**
 * Agent 3: batched day-committer.
 *
 * Takes the full set of [MaterialisedResolution]s from the worker
 * run, plus a small day summary, and emits the final list of
 * [Commit] records. Uses the engine's `probe()` path (a fresh
 * conversation per call) so the longer prompt doesn't pollute the
 * long-lived A1/A2 conversation.
 *
 * On failure: the worker falls back to "put every resolution in
 * `pending_review`" so we never write partial garbage.
 */
class Agent3DayCommitter(
    private val engine: GemmaInferenceEngine,
) {

    /**
     * @return the parsed [A3Contract] or null if the model returned
     *   nothing parseable. The worker uses null to trigger the
     *   "queue all to review" fallback.
     */
    suspend fun commit(
        resolutions: List<MaterialisedResolution>,
        daySummary: String,
    ): List<Commit>? = withContext(Dispatchers.IO) {
        if (resolutions.isEmpty()) return@withContext emptyList<Commit>()

        // Build a serialised list of A2 views from the materialised
        // candidates. A3 doesn't need DB context — the worker has
        // already resolved the candidates to row ids.
        val a2Views = resolutions.map { res ->
            val sourceChoice: SourceChoice = when (val s = res.sourceCandidate) {
                is com.spendai.app.domain.model.SourceCandidate.Existing ->
                    SourceChoice.Existing(s.sourceId, s.confidence)
                is com.spendai.app.domain.model.SourceCandidate.New ->
                    SourceChoice.New(
                        sourceKey = s.sourceKey,
                        deducedType = s.deducedType,
                        suggestedBankName = s.suggestedBankName,
                        suggestedInstrumentType = s.suggestedInstrumentType.name,
                        suggestedDisplayName = s.suggestedDisplayName,
                        confidence = s.confidence,
                    )
            }
            val accountChoice: AccountChoice = AccountChoice.Existing(
                accountId = res.accountId, confidence = 1f,
            )
            val merchantChoice: MerchantChoice = when {
                res.merchantId == null -> MerchantChoice.None(1f)
                else -> MerchantChoice.Existing(
                    merchantId = res.merchantId, confidence = 1f,
                )
            }
            val link = res.possibleLink?.let {
                LinkChoice(
                    partnerParsedSmsId = it.partnerTransactionId,
                    linkType = it.linkType.name,
                    confidence = it.confidence,
                )
            }
            A2Contract(
                parsedSmsId = res.parsedSmsId,
                source = sourceChoice,
                account = accountChoice,
                merchant = merchantChoice,
                possibleLink = link,
                a2Confidence = res.a2Confidence,
            )
        }
        val userMessage = buildString {
            append(AgentPrompt.JSON.encodeToString(
                ListSerializer(A2Contract.serializer()), a2Views
            ))
            append("\n\nDay summary:\n")
            append(daySummary)
        }
        val fullPrompt = AgentPrompt.A3_SYSTEM_INSTRUCTION + "\n\n" + userMessage

        val first = runCatching {
            engine.probe(fullPrompt)
        }.onFailure { Log.w(TAG, "A3 first attempt failed: ${it.message}") }.getOrNull()

        val firstParsed = first?.let { AgentJsonParse.tryParse(it, A3Contract.serializer()) }

        val contract = firstParsed ?: run {
            val retry = runCatching {
                engine.probe(AgentPrompt.A3_CORRECTIVE_PROMPT)
            }.onFailure { Log.w(TAG, "A3 retry failed: ${it.message}") }.getOrNull()
            retry?.let { AgentJsonParse.tryParse(it, A3Contract.serializer()) }
        } ?: return@withContext null

        contract.commits.mapNotNull { choice ->
            val res = resolutions.find { it.parsedSmsId == choice.parsedSmsId } ?: run {
                Log.w(TAG, "No matching resolution found for parsedSmsId=${choice.parsedSmsId}")
                null
            } ?: return@mapNotNull null

            val domainFinalTxn = choice.finalTransaction.toDomain().copy(
                accountId = res.accountId,
                merchantId = res.merchantId,
                rawSmsId = res.rawSmsId,
                parsedSmsId = res.parsedSmsId
            )

            Commit(
                parsedSmsId = res.parsedSmsId,
                finalTransaction = domainFinalTxn,
                confidence = choice.confidence,
                linksToCreate = choice.linksToCreate.map {
                    CommitLink(
                        partnerParsedSmsId = it.partnerParsedSmsId,
                        linkType = runCatching { TransactionLinkType.valueOf(it.linkType) }
                            .getOrDefault(TransactionLinkType.SELF_TRANSFER),
                        confidence = it.confidence,
                    )
                },
                needsReview = choice.needsReview,
            )
        }
    }

    private companion object {
        const val TAG = "Agent3DayCommitter"
    }
}

/**
 * Apply a list of [Commit]s to the database. Called by the worker
 * inside a single Room `@Transaction` so partial writes never land.
 *
 * The worker's pass over the commits builds a `parsedSmsId ->
 * transactionId` map for link resolution. The function returns that
 * map so the worker can also create any source-level pending_review
 * rows for new sources the user still needs to label.
 */
suspend fun applyCommits(
    commits: List<Commit>,
    transactionRepository: TransactionRepository,
    transactionLinkRepository: TransactionLinkRepository,
    pendingReviewRepository: PendingReviewRepository,
    sourceRepository: FinancialSourceRepository,
    now: Long,
): Map<Long, Long> {
    val parsedSmsToTxnId = mutableMapOf<Long, Long>()
    for (commit in commits) {
        val status = if (commit.needsReview) TransactionStatus.NEEDS_REVIEW
            else TransactionStatus.CONFIRMED
        val row = Transaction(
            accountId = commit.finalTransaction.accountId,
            merchantId = commit.finalTransaction.merchantId,
            rawSmsId = commit.finalTransaction.rawSmsId,
            parsedSmsId = commit.finalTransaction.parsedSmsId,
            amountPaise = commit.finalTransaction.amountPaise,
            currency = commit.finalTransaction.currency,
            direction = commit.finalTransaction.direction.name,
            txnAtMillis = commit.finalTransaction.txnAtMillis,
            channel = commit.finalTransaction.channel,
            referenceNo = commit.finalTransaction.referenceNo,
            status = status.name,
            confidence = commit.confidence,
            notes = commit.finalTransaction.notes,
            createdAt = now,
        )
        val txnId = transactionRepository.insert(row)
        parsedSmsToTxnId[commit.parsedSmsId] = txnId

        if (commit.needsReview || !Confidence.shouldAutoCommit(commit.confidence)) {
            pendingReviewRepository.insert(
                PendingReview(
                    kind = PendingReviewKind.TRANSACTION.name,
                    targetId = txnId,
                    promptSummary = buildString {
                        append("Amount: ")
                        append("%.2f".format(commit.finalTransaction.amountPaise / 100.0))
                        append(" ")
                        append(commit.finalTransaction.currency)
                        append(" ")
                        append(commit.finalTransaction.direction)
                        append(" via ")
                        append(commit.finalTransaction.channel ?: "unknown channel")
                    },
                    suggestedJson = AgentPrompt.JSON.encodeToString(
                        A3Contract.serializer(),
                        A3Contract(listOf(
                            CommitChoice(
                                parsedSmsId = commit.parsedSmsId,
                                finalTransaction = FinalTransactionChoice(
                                    accountId = commit.finalTransaction.accountId,
                                    merchantId = commit.finalTransaction.merchantId,
                                    rawSmsId = commit.finalTransaction.rawSmsId,
                                    parsedSmsId = commit.finalTransaction.parsedSmsId,
                                    amountPaise = commit.finalTransaction.amountPaise,
                                    currency = commit.finalTransaction.currency,
                                    direction = commit.finalTransaction.direction.name,
                                    txnAtMillis = commit.finalTransaction.txnAtMillis,
                                    channel = commit.finalTransaction.channel,
                                    referenceNo = commit.finalTransaction.referenceNo,
                                    status = commit.finalTransaction.status.name,
                                    notes = commit.finalTransaction.notes,
                                ),
                                confidence = commit.confidence,
                                needsReview = true,
                            )
                        ))
                    ),
                    createdAt = now,
                )
            )
        }
    }
    return parsedSmsToTxnId
}

/**
 * Create the directed edges for a batch, using the parsedSms-to-txn-id
 * map produced by [applyCommits]. Edges whose partner is not in the
 * map (e.g. partner was committed in a previous run) are skipped —
 * the worker resolves those by a separate DB lookup.
 */
suspend fun applyLinks(
    commits: List<Commit>,
    parsedSmsToTxnId: Map<Long, Long>,
    transactionLinkRepository: TransactionLinkRepository,
    now: Long,
) {
    for (commit in commits) {
        val fromId = parsedSmsToTxnId[commit.parsedSmsId] ?: continue
        for (link in commit.linksToCreate) {
            val toId = parsedSmsToTxnId[link.partnerParsedSmsId] ?: continue
            transactionLinkRepository.insertIgnore(
                TransactionLink(
                    fromTransactionId = fromId,
                    toTransactionId = toId,
                    linkType = link.linkType.name,
                    confidence = link.confidence,
                    createdAt = now,
                )
            )
        }
    }
}

/**
 * Promote any [SourceStatus.NEEDS_REVIEW] sources discovered in this
 * run to the user's daily review queue as `kind=SOURCE` cards.
 */
suspend fun queueNewSourceReviews(
    resolutions: List<MaterialisedResolution>,
    sourceRepository: FinancialSourceRepository,
    pendingReviewRepository: PendingReviewRepository,
    now: Long,
) {
    for (res in resolutions) {
        val src = sourceRepository.findByKey(
            (res.sourceCandidate as? com.spendai.app.domain.model.SourceCandidate.New)?.sourceKey
                ?: continue
        ) ?: continue
        if (src.status == SourceStatus.NEEDS_REVIEW.name) {
            pendingReviewRepository.insert(
                PendingReview(
                    kind = PendingReviewKind.SOURCE.name,
                    targetId = src.id,
                    promptSummary = "New sender detected: ${src.sourceKey}. " +
                        "Please label it (bank, instrument type).",
                    suggestedJson = AgentPrompt.JSON.encodeToString(
                        kotlinx.serialization.json.JsonObject.serializer(),
                        kotlinx.serialization.json.JsonObject(mapOf(
                            "sourceKey" to kotlinx.serialization.json.JsonPrimitive(src.sourceKey),
                            "deducedType" to kotlinx.serialization.json.JsonPrimitive(src.deducedType),
                            "suggestedBankName" to kotlinx.serialization.json.JsonPrimitive(src.bankName ?: ""),
                            "suggestedInstrumentType" to kotlinx.serialization.json.JsonPrimitive(src.instrumentType),
                            "suggestedDisplayName" to kotlinx.serialization.json.JsonPrimitive(src.displayName ?: ""),
                        ))
                    ),
                    createdAt = now,
                )
            )
        }
    }
}
