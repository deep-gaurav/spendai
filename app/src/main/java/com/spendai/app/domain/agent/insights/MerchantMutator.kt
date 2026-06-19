package com.spendai.app.domain.agent.insights

import android.util.Log
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.Merchant
import com.spendai.app.data.local.entity.MerchantMetadataKind
import com.spendai.app.data.local.entity.RepromptJob
import com.spendai.app.data.local.entity.RepromptJobStatus
import com.spendai.app.data.local.entity.TransactionLink
import com.spendai.app.data.local.entity.TransactionLinkType
import com.spendai.app.data.repository.MerchantRepository
import com.spendai.app.data.repository.RepromptJobRepository
import com.spendai.app.domain.model.MerchantNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * The narrow, allowlisted write path the Ask-AI flow uses to
 * save user-defined merchant knowledge.
 *
 * ## Why a separate mutator
 *
 * The LLM can produce arbitrary SQL, so [SqlExecutor] is a
 * structural safety boundary: it parses, comment-strips, and
 * verifies the read-only / single-statement / allowlisted-table
 * rules before running. We do NOT relax those rules to also
 * accept `INSERT` / `UPDATE` / `DELETE`; instead we expose a
 * parameterised mutation API with a fixed shape. The mutator
 * is the only path that can write to `merchant` and
 * `merchant_metadata` from the LLM, and it can only set the
 * `isSelf` boolean or upsert/delete `merchant_metadata` rows.
 *
 * ## Self-link ripple
 *
 * When the model flips `isSelf = true` on a merchant, the
 * mutator walks the existing transactions for that merchant
 * and:
 *  - For each transaction, finds the best transfer partner
 *    (opposite direction, similar amount, within ±3 days,
 *    on a different account) and writes a `SELF_TRANSFER` link
 *    row. The existing InsightsDao `NOT EXISTS` predicate
 *    drops both rows from every aggregate.
 *  - Enqueues a [RepromptJob] per affected transaction so
 *    the IngestionService cold-start scan re-runs A3 with
 *    the new metadata injected. The job is durable; a
 *    process death does not drop the user's intent.
 *
 * The mutator does NOT modify the spend_transaction rows
 * themselves. The user said "transactions themselves are
 * fine" - they just want the transactions to drop out of
 * insights and be re-categorised by A3 on the reprompt.
 */
class MerchantMutator(
    private val database: AppDatabase,
    private val merchantRepository: MerchantRepository,
    private val repromptJobRepository: RepromptJobRepository,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val selfLinkLookbackDays: Int = 3,
    private val selfLinkAmountTolerancePct: Double = 0.10,
) {
    companion object {
        const val TAG = "MerchantMutator"
        const val MAX_AFFECTED_TXNS_FOR_REPROMPT = 50
    }

    /**
     * Result of a single mutation. The orchestrator turns this
     * into a
     * [com.spendai.app.domain.agent.insights.AgenticInsightsMessage.ToolResultMessage]
     * so the model can describe what happened in plain English
     * on the next turn. The fields are deliberately flat - the
     * model does not need the full structure, just the
     * counters.
     */
    @Serializable
    data class MutationResult(
        val matchedMerchantId: Long?,
        val matchedMerchantName: String?,
        val isSelfChanged: Boolean,
        val isSelfNewValue: Boolean,
        val metadataAdded: List<MetadataOpRecord> = emptyList(),
        val metadataRemoved: List<String> = emptyList(),
        val affectedTransactionIds: List<Long> = emptyList(),
        val selfTransferLinksWritten: Int = 0,
        val repromptsEnqueued: Int = 0,
        val error: String? = null,
    ) {
        @Serializable
        data class MetadataOpRecord(val kind: String, val value: String)
    }

    /**
     * Run a [AgenticAction.MutateMerchant] against the user's
     * database. Validates the action against the mutator's
     * allowlist before touching any row; on any rejection the
     * result carries an `error` string and no row is modified.
     */
    suspend fun mutate(action: AgenticAction.MutateMerchant): MutationResult =
        withContext(Dispatchers.IO) {
            val merchant = resolveMerchant(action)
                ?: return@withContext MutationResult(
                    matchedMerchantId = null,
                    matchedMerchantName = null,
                    isSelfChanged = false,
                    isSelfNewValue = false,
                    error = "No merchant matched the name/id in the action.",
                )

            // Each individual op is also validated: a metadata
            // kind outside the enum is rejected without
            // touching any other op that came in the same
            // batch.
            val validatedAdds = mutableListOf<MerchantMetadataKind>()
            for (op in action.addMetadata) {
                val kind = parseKind(op.kind) ?: return@withContext MutationResult(
                    matchedMerchantId = merchant.id,
                    matchedMerchantName = merchant.name,
                    isSelfChanged = false,
                    isSelfNewValue = merchant.isSelf,
                    error = "Unknown metadata kind '" + op.kind + "'. Allowed: " +
                        MerchantMetadataKind.values().joinToString(",") { it.name },
                )
                if (op.value.isBlank()) {
                    return@withContext MutationResult(
                        matchedMerchantId = merchant.id,
                        matchedMerchantName = merchant.name,
                        isSelfChanged = false,
                        isSelfNewValue = merchant.isSelf,
                        error = "Metadata value for kind '" + kind.name + "' was blank.",
                    )
                }
                validatedAdds += kind
            }
            val validatedRemoves = mutableListOf<MerchantMetadataKind>()
            for (kindName in action.removeMetadata) {
                val kind = parseKind(kindName) ?: return@withContext MutationResult(
                    matchedMerchantId = merchant.id,
                    matchedMerchantName = merchant.name,
                    isSelfChanged = false,
                    isSelfNewValue = merchant.isSelf,
                    error = "Unknown metadata kind '" + kindName + "' in removeMetadata.",
                )
                validatedRemoves += kind
            }

            // isSelf flip
            val desiredIsSelf = when {
                action.setIsSelf == true -> true
                action.clearIsSelf == true -> false
                else -> merchant.isSelf
            }
            val isSelfChanged = desiredIsSelf != merchant.isSelf
            if (isSelfChanged) {
                merchantRepository.setIsSelf(merchant.id, desiredIsSelf)
            }

            // metadata upserts
            val now = nowMillis()
            val addedRecords = mutableListOf<MutationResult.MetadataOpRecord>()
            for ((idx, kind) in validatedAdds.withIndex()) {
                val value = action.addMetadata[idx].value.trim()
                merchantRepository.putMetadata(merchant.id, kind, value, now)
                addedRecords += MutationResult.MetadataOpRecord(kind.name, value)
            }
            for (kind in validatedRemoves) {
                merchantRepository.removeMetadata(merchant.id, kind)
            }

            // self-link ripple + reprompt enqueue
            val affectedIds = if (isSelfChanged && desiredIsSelf) {
                findAffectedTransactionIds(merchant.id)
            } else emptyList()
            val linksWritten = if (isSelfChanged && desiredIsSelf) {
                writeSelfTransferLinks(affectedIds)
            } else 0
            val repromptsEnqueued = if (isSelfChanged && desiredIsSelf || addedRecords.isNotEmpty()) {
                enqueueReprompts(merchant, affectedIds, addedRecords, isSelfChanged)
            } else 0

            Log.i(
                TAG,
                "mutate: merchant=" + merchant.name + " isSelf=" + desiredIsSelf +
                    " added=" + addedRecords.size + " removed=" + validatedRemoves.size +
                    " affected=" + affectedIds.size + " links=" + linksWritten +
                    " reprompts=" + repromptsEnqueued,
            )
            MutationResult(
                matchedMerchantId = merchant.id,
                matchedMerchantName = merchant.name,
                isSelfChanged = isSelfChanged,
                isSelfNewValue = desiredIsSelf,
                metadataAdded = addedRecords,
                metadataRemoved = validatedRemoves.map { it.name },
                affectedTransactionIds = affectedIds,
                selfTransferLinksWritten = linksWritten,
                repromptsEnqueued = repromptsEnqueued,
            )
        }

    private suspend fun resolveMerchant(action: AgenticAction.MutateMerchant): Merchant? {
        if (action.matchById != null) {
            return merchantRepository.getById(action.matchById)
        }
        val raw = action.matchByName?.takeIf { it.isNotBlank() } ?: return null
        val normalized = MerchantNormalizer.normalize(raw)
        if (normalized.isEmpty()) return null
        return merchantRepository.findByNormalizedName(normalized)
    }

    private fun parseKind(raw: String): MerchantMetadataKind? =
        runCatching { MerchantMetadataKind.valueOf(raw.trim().uppercase()) }.getOrNull()

    /**
     * Walk the spend_transaction table for rows whose
     * `merchantId` matches. We use the existing
     * `getSince(0L)` query so we don't have to add a new DAO
     * method just for the mutator. The result is bounded by
     * the user's transaction count (a few hundred at most on
     * a personal-finance install).
     */
    private suspend fun findAffectedTransactionIds(merchantId: Long): List<Long> {
        return database.transactionDao()
            .getSince(0L)
            .asSequence()
            .filter { it.merchantId == merchantId }
            .map { it.id }
            .toList()
    }

    /**
     * For each affected transaction, find the best transfer
     * partner: opposite direction, similar amount, within
     * the lookback window, on a different account. When a
     * partner exists, write a SELF_TRANSFER `transaction_link`
     * row. The InsightsDao `NOT EXISTS` predicate drops the
     * pair from every aggregate without us touching the
     * spend_transaction rows themselves.
     *
     * The partner pool is the full transaction table, not
     * just the affected rows. The affected set is the
     * self-merchant side of a transfer; the partner is the
     * other side, which is on a different account and may
     * be any merchant (often the user's own wallet or
     * another card).
     */
    private suspend fun writeSelfTransferLinks(affectedTransactionIds: List<Long>): Int {
        if (affectedTransactionIds.isEmpty()) return 0
        val affected = database.transactionDao().getSince(0L)
            .filter { it.id in affectedTransactionIds }
        val candidatesByDirection = database.transactionDao().getSince(0L)
            .groupBy { it.direction }
        val windowMs = selfLinkLookbackDays * 24L * 60L * 60L * 1000L
        val tolerance = selfLinkAmountTolerancePct
        var written = 0
        val now = nowMillis()
        for (txn in affected) {
            val partnerDir = when (txn.direction) {
                "DEBIT" -> "CREDIT"
                "CREDIT" -> "DEBIT"
                else -> continue
            }
            val partner = candidatesByDirection[partnerDir].orEmpty().firstOrNull { other ->
                other.id != txn.id &&
                    other.accountId != txn.accountId &&
                    kotlin.math.abs(other.txnAtMillis - txn.txnAtMillis) <= windowMs &&
                    amountWithinTolerance(txn.amountPaise, other.amountPaise, tolerance)
            } ?: continue
            database.transactionLinkDao().insertIgnore(
                TransactionLink(
                    fromTransactionId = txn.id,
                    toTransactionId = partner.id,
                    linkType = TransactionLinkType.SELF_TRANSFER.name,
                    confidence = 0.85f,
                    createdAt = now,
                )
            )
            written++
        }
        return written
    }

    private fun amountWithinTolerance(a: Long, b: Long, pct: Double): Boolean {
        if (a == 0L || b == 0L) return a == b
        val lower = a * (1.0 - pct)
        val upper = a * (1.0 + pct)
        return b.toDouble() >= lower && b.toDouble() <= upper
    }

    /**
     * Enqueue durable A3 reprompt jobs for every affected
     * transaction. Capped at [MAX_AFFECTED_TXNS_FOR_REPROMPT]
     * so a single mutate_merchant call cannot enqueue hundreds
     * of jobs (e.g. if the user marks "Mohan" as self and that
     * merchant has 200 rows). The remaining rows are still
     * excluded from insights via the isSelf flag and the
     * self-transfer links; they just don't get an A3
     * re-categorise until the user explicitly asks for one.
     */
    private suspend fun enqueueReprompts(
        merchant: Merchant,
        affectedIds: List<Long>,
        added: List<MutationResult.MetadataOpRecord>,
        isSelfChanged: Boolean,
    ): Int {
        if (affectedIds.isEmpty()) return 0
        val prompt = buildPrompt(merchant, added, isSelfChanged)
        var enqueued = 0
        val limit = affectedIds.take(MAX_AFFECTED_TXNS_FOR_REPROMPT)
        for (txnId in limit) {
            val id = repromptJobRepository.insert(
                RepromptJob(
                    rawSmsIds = "[]",
                    userPrompt = prompt,
                    transactionId = txnId,
                    createdAt = nowMillis(),
                    status = RepromptJobStatus.PENDING.name,
                )
            )
            if (id > 0L) enqueued++
        }
        return enqueued
    }

    private fun buildPrompt(
        merchant: Merchant,
        added: List<MutationResult.MetadataOpRecord>,
        isSelfChanged: Boolean,
    ): String = buildString {
        append("User updated merchant '").append(merchant.name).append("' (id=")
            .append(merchant.id).append("). ")
        if (isSelfChanged) {
            append("Marked isSelf=true. This is the user themself. ")
        }
        if (added.isNotEmpty()) {
            append("Metadata now: ")
            added.forEachIndexed { i, op ->
                if (i > 0) append("; ")
                append(op.kind).append(" = ").append(op.value)
            }
            append(". Re-evaluate this transaction's merchant / category / title to reflect the new context.")
        }
    }
}
