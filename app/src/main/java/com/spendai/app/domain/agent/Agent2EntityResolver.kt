package com.spendai.app.domain.agent

import android.util.Log
import com.spendai.app.data.local.entity.Account
import com.spendai.app.data.local.entity.FinancialSource
import com.spendai.app.data.local.entity.Merchant
import com.spendai.app.data.local.entity.ParsedSms
import com.spendai.app.data.local.entity.SourceInstrumentType
import com.spendai.app.data.local.entity.SourceStatus
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.repository.AccountRepository
import com.spendai.app.data.repository.FinancialSourceRepository
import com.spendai.app.data.repository.MerchantRepository
import com.spendai.app.data.repository.TransactionRepository
import com.spendai.app.domain.model.Confidence
import com.spendai.app.domain.model.MerchantNormalizer
import com.spendai.app.domain.model.Resolution
import com.spendai.app.inference.GemmaInferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

/**
 * Agent 2: per-message entity resolver.
 *
 * Takes a freshly parsed [ParsedSms] and a same-day [ResolutionContext]
 * bundle (known sources, accounts, merchants, recent transactions) and
 * decides:
 *  - which financial source / account / merchant (existing or new)
 *  - whether this transaction is the other half of a known transaction
 *    (self-transfer, refund, reversal)
 *
 * On model failure, throws — the worker turns that into `Result.retry()`.
 */
class Agent2EntityResolver(
    private val engine: GemmaInferenceEngine,
    private val sourceRepository: FinancialSourceRepository,
    private val accountRepository: AccountRepository,
    private val merchantRepository: MerchantRepository,
    private val transactionRepository: TransactionRepository,
) {
    private companion object {
        const val TAG = "Agent2EntityResolver"
        const val LOG_TRUNCATE_CHARS = 400
        const val SAME_DAY_WINDOW_MILLIS: Long = 24L * 60L * 60L * 1000L
        fun truncate(s: String?): String {
            if (s == null) return "<null>"
            return if (s.length <= LOG_TRUNCATE_CHARS) s
            else s.take(LOG_TRUNCATE_CHARS) + "...[+${s.length - LOG_TRUNCATE_CHARS} chars]"
        }
    }



    /**
     * Build the [ResolutionContext] from the live DB state and pass it
     * along with the parsed SMS to the model. Returns the in-memory
     * [Resolution] — the worker hands a list of these to Agent 3.
     */
    suspend fun resolve(parsed: ParsedSms): Resolution = withContext(Dispatchers.IO) {
        val context = loadContext(parsed.txnAtMillis ?: System.currentTimeMillis())
        val userMessage = AgentPrompt.a2UserMessage(parsed, context.toBundleJson())
        val fullPrompt = AgentPrompt.A2_SYSTEM_INSTRUCTION + "\n\n" + userMessage

        // Per-token progress is published into InferenceState.Busy so the
        // home card can show "Decoded 142 tokens (agent2.resolve) · 8s".
        Log.d(TAG, "A2 input parsedSmsId=${parsed.id} a1RawJson: ${truncate(parsed.a1RawJson)}")
        Log.d(TAG, "A2 prompt sent to model (${fullPrompt.length} chars): ${truncate(fullPrompt)}")
        val first = runCatching {
            engine.generatePredictionTracking(
                prompt = fullPrompt,
                stepLabel = "agent2.resolve",
            ).toList().joinToString("")
        }.onFailure { Log.w(TAG, "A2 first attempt failed: ${it.message}") }
            .getOrNull()
        Log.d(TAG, "A2 raw model response (${first?.length ?: 0} chars): ${truncate(first)}")
        val firstParsed = first?.let { AgentJsonParse.tryParse(it, A2Contract.serializer()) }
        Log.d(TAG, "A2 first-try parse: ${if (firstParsed != null) "OK a2Confidence=${firstParsed.a2Confidence}" else "FAILED (will retry)"}")

        val contract = firstParsed ?: run {
            val retry = runCatching {
                engine.generatePredictionTracking(
                    prompt = AgentPrompt.A2_CORRECTIVE_PROMPT,
                    stepLabel = "agent2.resolve.retry",
                ).toList().joinToString("")
            }.onFailure { Log.w(TAG, "A2 retry failed: ${it.message}") }
                .getOrNull()
            Log.d(TAG, "A2 retry raw model response (${retry?.length ?: 0} chars): ${truncate(retry)}")
            retry?.let { AgentJsonParse.tryParse(it, A2Contract.serializer()) }
        } ?: throw IllegalStateException("Agent 2 returned no parseable JSON for parsedSmsId=${parsed.id}")
        Log.d(TAG, "A2 final contract: a2Confidence=${contract.a2Confidence}")

        contract.toResolution(parsed.id).also {
            // Defensive: clamp confidence into [0, 1] in case the model
            // emitted something outside the contract.
            require(it.a2Confidence in 0f..1f) {
                "a2Confidence out of range: ${it.a2Confidence}"
            }
        }
    }

    private suspend fun loadContext(centerMillis: Long): ResolutionContext {
        val sources = sourceRepository.allOnce().map {
            ContextSource(
                id = it.id,
                sourceKey = it.sourceKey,
                displayName = it.displayName ?: it.userLabel,
                instrumentType = it.instrumentType,
                status = it.status,
            )
        }
        val accounts = accountRepository.getAllOnce().map {
            ContextAccount(
                id = it.id,
                sourceId = it.sourceId,
                instrumentType = it.instrumentType,
                issuer = it.issuer,
                maskedNumber = it.maskedNumber,
                currency = it.currency,
            )
        }
        val merchants = merchantRepository.getAllOnce().map {
            ContextMerchant(
                id = it.id, name = it.name,
                normalizedName = it.normalizedName, vpa = it.vpa,
            )
        }
        val since = centerMillis - SAME_DAY_WINDOW_MILLIS
        val txns = transactionRepository.getSince(since).map { txn ->
            val merchantName = txn.merchantId
                ?.let { merchantRepository.getById(it)?.name }
            val accountMasked = accountRepository.getById(txn.accountId)?.maskedNumber
                ?: "unknown"
            ContextTransaction(
                id = txn.id,
                parsedSmsId = txn.parsedSmsId,
                amountPaise = txn.amountPaise,
                currency = txn.currency,
                direction = txn.direction,
                txnAtMillis = txn.txnAtMillis,
                merchantName = merchantName,
                accountMasked = accountMasked,
            )
        }
        return ResolutionContext(
            knownSources = sources,
            knownAccounts = accounts,
            knownMerchants = merchants,
            recentTransactions = txns,
        )
    }

}



/**
 * In-memory bundle of everything the resolver needs to ground its
 * decisions: the rows already in the local DB plus the recent
 * transactions that might be the other half of a self-transfer.
 * Serialized to a flat JSON string by [toBundleJson] and concatenated
 * with the parsed SMS as the A2 user message.
 */
data class ResolutionContext(
    val knownSources: List<ContextSource> = emptyList(),
    val knownAccounts: List<ContextAccount> = emptyList(),
    val knownMerchants: List<ContextMerchant> = emptyList(),
    val recentTransactions: List<ContextTransaction> = emptyList(),
) {
    fun toBundleJson(): String = buildString {
        append("{\"knownSources\":[")
        knownSources.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append("{\"id\":").append(s.id)
                .append(",\"sourceKey\":\"").append(escape(s.sourceKey)).append("\"")
                .append(",\"displayName\":").append(quoteOrNull(s.displayName))
                .append(",\"instrumentType\":").append(quoteOrNull(s.instrumentType))
                .append(",\"status\":").append(quoteOrNull(s.status))
                .append('}')
        }
        append("],\"knownAccounts\":[")
        knownAccounts.forEachIndexed { i, a ->
            if (i > 0) append(',')
            append("{\"id\":").append(a.id)
                .append(",\"sourceId\":").append(a.sourceId)
                .append(",\"instrumentType\":").append(quoteOrNull(a.instrumentType))
                .append(",\"issuer\":").append(quoteOrNull(a.issuer))
                .append(",\"maskedNumber\":").append(quoteOrNull(a.maskedNumber))
                .append(",\"currency\":").append(quoteOrNull(a.currency))
                .append('}')
        }
        append("],\"knownMerchants\":[")
        knownMerchants.forEachIndexed { i, m ->
            if (i > 0) append(',')
            append("{\"id\":").append(m.id)
                .append(",\"name\":\"").append(escape(m.name)).append("\"")
                .append(",\"normalizedName\":\"").append(escape(m.normalizedName)).append("\"")
                .append('}')
        }
        append("],\"recentTransactions\":[")
        recentTransactions.forEachIndexed { i, t ->
            if (i > 0) append(',')
            append("{\"id\":").append(t.id)
                .append(",\"parsedSmsId\":").append(t.parsedSmsId)
                .append(",\"amountPaise\":").append(t.amountPaise)
                .append(",\"currency\":\"").append(escape(t.currency)).append("\"")
                .append(",\"direction\":\"").append(escape(t.direction)).append("\"")
                .append(",\"txnAtMillis\":").append(t.txnAtMillis)
                .append(",\"merchantName\":").append(quoteOrNull(t.merchantName))
                .append(",\"accountMasked\":\"").append(escape(t.accountMasked)).append("\"")
                .append('}')
        }
        append("]}")
    }
    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun quoteOrNull(s: String?): String =
        if (s == null) "null" else "\"" + escape(s) + "\""
}

data class ContextSource(
    val id: Long,
    val sourceKey: String,
    val displayName: String?,
    val instrumentType: String?,
    val status: String?,
)

data class ContextAccount(
    val id: Long,
    val sourceId: Long,
    val instrumentType: String?,
    val issuer: String?,
    val maskedNumber: String?,
    val currency: String?,
)

data class ContextMerchant(
    val id: Long,
    val name: String,
    val normalizedName: String,
    val vpa: String?,
)

data class ContextTransaction(
    val id: Long,
    val parsedSmsId: Long,
    val amountPaise: Long,
    val currency: String,
    val direction: String,
    val txnAtMillis: Long,
    val merchantName: String?,
    val accountMasked: String,
)

/**
 * Helper for the worker: pre-compute the candidates' inserted IDs
 * (creating any new source / account / merchant rows) so Agent 3
 * can reference them by id in its final [Commit] output.
 */
suspend fun Resolution.materialise(
    sourceRepository: FinancialSourceRepository,
    accountRepository: AccountRepository,
    merchantRepository: MerchantRepository,
    rawSmsId: Long,
    now: Long,
): MaterialisedResolution {
    val sourceId = when (val s = sourceCandidate) {
        is com.spendai.app.domain.model.SourceCandidate.Existing -> s.sourceId
        is com.spendai.app.domain.model.SourceCandidate.New -> {
            sourceRepository.findByKey(s.sourceKey)?.id ?: sourceRepository.upsert(
                FinancialSource(
                    sourceKey = s.sourceKey,
                    deducedType = s.deducedType,
                    userLabel = s.suggestedDisplayName,
                    firstSeenTimestamp = now,
                    displayName = s.suggestedDisplayName,
                    bankName = s.suggestedBankName,
                    instrumentType = s.suggestedInstrumentType.name,
                    status = if (s.confidence >= Confidence.AUTO_COMMIT_THRESHOLD)
                        SourceStatus.CONFIRMED.name
                    else SourceStatus.NEEDS_REVIEW.name,
                    confirmedAt = if (s.confidence >= Confidence.AUTO_COMMIT_THRESHOLD) now else null,
                )
            )
        }
    }

    val accountId = when (val a = accountCandidate) {
        is com.spendai.app.domain.model.AccountCandidate.Existing -> a.accountId
        is com.spendai.app.domain.model.AccountCandidate.New -> {
            accountRepository.findBySourceAndMasked(sourceId, a.maskedNumber)?.id
                ?: accountRepository.insert(
                    Account(
                        sourceId = sourceId,
                        instrumentType = a.instrumentType.name,
                        issuer = a.issuer,
                        maskedNumber = a.maskedNumber,
                        currency = a.currency,
                        createdAt = now,
                    )
                )
        }
    }

    val merchantId: Long? = when (val m = merchantCandidate) {
        is com.spendai.app.domain.model.MerchantCandidate.Existing -> m.merchantId
        is com.spendai.app.domain.model.MerchantCandidate.New -> {
            val normalized = MerchantNormalizer.normalize(m.name).ifEmpty { m.normalizedName }
            merchantRepository.findByNormalizedName(normalized)?.id
                ?: merchantRepository.findByVpa(m.vpa ?: "")?.id
                ?: run {
                    val row = Merchant(
                        name = m.name,
                        normalizedName = normalized,
                        vpa = m.vpa,
                        firstSeenAt = now,
                    )
                    merchantRepository.insert(row)
                }
        }
        is com.spendai.app.domain.model.MerchantCandidate.None -> null
    }

    return MaterialisedResolution(
        parsedSmsId = parsedSmsId,
        rawSmsId = rawSmsId,
        sourceId = sourceId,
        accountId = accountId,
        merchantId = merchantId,
        possibleLink = possibleLink,
        a2Confidence = a2Confidence,
        sourceCandidate = sourceCandidate,
    )
}

data class MaterialisedResolution(
    val parsedSmsId: Long,
    val rawSmsId: Long,
    val sourceId: Long,
    val accountId: Long,
    val merchantId: Long?,
    val possibleLink: com.spendai.app.domain.model.PossibleLink?,
    val a2Confidence: Float,
    val sourceCandidate: com.spendai.app.domain.model.SourceCandidate,
)
