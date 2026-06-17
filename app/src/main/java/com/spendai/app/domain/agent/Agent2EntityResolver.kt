package com.spendai.app.domain.agent

import android.util.Log
import androidx.room.withTransaction
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.Account
import com.spendai.app.data.local.entity.Category
import com.spendai.app.data.local.entity.FinancialSource
import com.spendai.app.data.local.entity.Merchant
import com.spendai.app.data.local.entity.ParsedSms
import com.spendai.app.data.local.entity.SourceStatus
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.local.entity.TransactionDirection
import com.spendai.app.data.local.entity.TransactionStatus
import com.spendai.app.data.repository.AccountRepository
import com.spendai.app.data.repository.CategoryRepository
import com.spendai.app.data.repository.FinancialSourceRepository
import com.spendai.app.data.repository.MerchantRepository
import com.spendai.app.data.repository.TransactionRepository
import com.spendai.app.domain.model.MerchantNormalizer
import com.spendai.app.inference.GemmaInferenceEngine
import com.spendai.app.inference.InferenceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

/**
 * Agent 2: per-message entity resolver AND committer.
 *
 * Takes a freshly parsed [ParsedSms] (A1 said TRANSACTION) and:
 *  1. Loads a small slice of the local DB (top-N sources, accounts,
 *     merchants by recency) into a prompt bundle.
 *  2. Asks the model to link the transaction to existing rows
 *     or propose new ones. The model also emits a freeform title
 *     and a category name + emoji.
 *  3. Materialises the source / account / merchant / category rows
 *     (insert when "new", dedup on the existing indices).
 *  4. Writes the `spend_transaction` row.
 *
 * Steps 3 and 4 happen inside a single Room `@Transaction` so the
 * per-message commit is atomic. Returns the new transaction id.
 *
 * ## Auto-commit
 *
 * A2 commits every message A1 marked as a transaction. Confidence
 * is preserved on the row for the edit UI to display but does NOT
 * gate the commit. A1's `kind=IGNORE` is the only "don't commit"
 * signal and is handled by the pipeline before A2 is called.
 *
 * ## Day grouping fix
 *
 * `txnAtMillis` is taken from the SMS receive timestamp (passed in
 * by the pipeline) and is no longer derived from the model's
 * `parsed.txnAtMillis`. This means an SMS received at 23:59 and
 * one received at 00:01 always end up in their respective receive
 * days, even if the model guesses a slightly different transaction
 * time. The model's `txnAtMillis` is still kept on `parsed_sms`
 * for the audit log.
 *
 * ## Categories
 *
 * The LLM emits a `categoryName` + `categoryEmoji` pair. The
 * resolver looks the name up by its normalised form and creates a
 * new [Category] row on first sight, preserving the LLM's emoji.
 * Reusing a name always returns the existing row (the original
 * emoji wins). The transaction and merchant store the FK.
 *
 * ## Prompt size
 *
 * The merchant slice is capped at 100 rows by `firstSeenAt DESC` so
 * the input bundle stays well under the 64K total context. Sources
 * and accounts are also defensively capped (20 and 50). New senders
 * land at the top of the recency list immediately because A2 calls
 * this method sequentially per message.
 */
class Agent2EntityResolver(
    private val engine: GemmaInferenceEngine,
    private val database: AppDatabase,
    private val sourceRepository: FinancialSourceRepository,
    private val accountRepository: AccountRepository,
    private val merchantRepository: MerchantRepository,
    private val categoryRepository: CategoryRepository,
    private val transactionRepository: TransactionRepository,
) {
    private companion object {
        const val TAG = "Agent2EntityResolver"
        const val LOG_TRUNCATE_CHARS = 400
        const val MAX_MERCHANTS = 100
        const val MAX_SOURCES = 20
        const val MAX_ACCOUNTS = 50
        const val A2_MAX_OUTPUT_TOKENS = 65536
        fun truncate(s: String?): String {
            if (s == null) return "<null>"
            return if (s.length <= LOG_TRUNCATE_CHARS) s
            else s.take(LOG_TRUNCATE_CHARS) + "...[+${s.length - LOG_TRUNCATE_CHARS} chars]"
        }
    }

    /**
     * @param parsed the [ParsedSms] row A1 produced.
     * @param smsTimestampMillis the raw SMS receive timestamp. Used
     *   as the transaction's [Transaction.txnAtMillis] so the
     *   transaction lives in the same calendar day the user saw the
     *   message. Per the v0.6 design, the model's txnAtMillis is no
     *   longer copied onto the row.
     * @return the new [Transaction.id]. Throws on model failure
     *   (no parseable JSON, or `a2Confidence` out of range) so the
     *   pipeline can route to `skippedByA2++` and leave the
     *   raw_sms row UNPARSED for a future retry.
     */
    suspend fun resolveAndCommit(
        parsed: ParsedSms,
        smsTimestampMillis: Long,
    ): A2Outcome = withContext(Dispatchers.IO) {
        require(engine.state.value is InferenceState.Ready) {
            "Engine not READY (state=${engine.state.value}). Call initialize() first."
        }
        val context = loadContext()
        val userMessage = AgentPrompt.a2UserMessage(parsed, context.toBundleJson())
        val fullPrompt = AgentPrompt.A2_SYSTEM_INSTRUCTION + "\n\n" + userMessage

        Log.d(TAG, "A2 input parsedSmsId=${parsed.id} smsTs=$smsTimestampMillis a1RawJson: ${truncate(parsed.a1RawJson)}")
        Log.d(TAG, "A2 prompt sent to model (${fullPrompt.length} chars): ${truncate(fullPrompt)}")
        val firstText: String? = runCatching {
            engine.generatePredictionTracking(
                prompt = fullPrompt,
                stepLabel = "agent2.resolve",
                maxOutputTokens = A2_MAX_OUTPUT_TOKENS,
            ).toList().joinToString("")
        }.onFailure { Log.w(TAG, "A2 first attempt failed: ${it.message}") }
            .getOrNull()
        Log.d(TAG, "A2 raw model response (${firstText?.length ?: 0} chars): ${truncate(firstText)}")
        val firstParsed = firstText?.let { AgentJsonParse.tryParse(it, A2Contract.serializer()) }
        Log.d(TAG, "A2 first-try parse: ${if (firstParsed != null) "OK a2Confidence=${firstParsed.a2Confidence} title=${firstParsed.title} cat=${firstParsed.categoryName}/${firstParsed.categoryEmoji}" else "FAILED (will retry)"}")

        // If firstParsed is non-null, firstText is necessarily non-null
        // (firstParsed was derived from it). Same logic on the retry
        // branch.
        val (contract, responseText) = if (firstParsed != null) {
            firstParsed to firstText
        } else {
            val retryText: String? = runCatching {
                engine.generatePredictionTracking(
                    prompt = AgentPrompt.A2_CORRECTIVE_PROMPT,
                    stepLabel = "agent2.resolve.retry",
                    maxOutputTokens = A2_MAX_OUTPUT_TOKENS,
                ).toList().joinToString("")
            }.onFailure { Log.w(TAG, "A2 retry failed: ${it.message}") }
                .getOrNull()
            Log.d(TAG, "A2 retry raw model response (${retryText?.length ?: 0} chars): ${truncate(retryText)}")
            val retryParsed = retryText?.let { AgentJsonParse.tryParse(it, A2Contract.serializer()) }
            if (retryParsed != null) {
                retryParsed to retryText
            } else {
                // Carry the prompt (always available) and the
                // better of the two responses (retry if it ran, else
                // first) so the audit row can show what the model
                // actually emitted before failing.
                val partial = retryText ?: firstText
                throw A2FailureException(
                    prompt = fullPrompt,
                    response = partial,
                    cause = IllegalStateException(
                        "Agent 2 returned no parseable JSON for parsedSmsId=${parsed.id}"
                    ),
                )
            }
        }
        Log.d(TAG, "A2 final contract: a2Confidence=${contract.a2Confidence} title=${contract.title} cat=${contract.categoryName}/${contract.categoryEmoji}")

        // Defensive: clamp confidence into [0, 1] in case the model
        // emitted something outside the contract.
        require(contract.a2Confidence in 0f..1f) {
            "a2Confidence out of range: ${contract.a2Confidence}"
        }

        val now = System.currentTimeMillis()
        val txnId = database.withTransaction {
            val sourceId = materialiseSource(contract.source, now)
            val accountId = materialiseAccount(sourceId, contract.account, now)
            val merchantRow = materialiseMerchant(contract.merchant, contract, now)
            val categoryId = materialiseCategory(contract, now)
            val txn = buildTransaction(
                parsed = parsed,
                accountId = accountId,
                merchantId = merchantRow?.id,
                categoryId = categoryId?.id,
                contractTitle = contract.title,
                smsTimestampMillis = smsTimestampMillis,
                a2Confidence = contract.a2Confidence,
                now = now,
            )
            insertTransaction(txn)
        }
        Log.d(TAG, "A2 committed transactionId=$txnId parsedSmsId=${parsed.id} categoryId=${contract.categoryName}")
        A2Outcome(
            transactionId = txnId,
            prompt = fullPrompt,
            response = responseText,
            a2Confidence = contract.a2Confidence,
        )
    }

    private suspend fun loadContext(): ResolutionContext {
        val sources = sourceRepository.allOnce().take(MAX_SOURCES).map {
            ContextSource(
                id = it.id,
                sourceKey = it.sourceKey,
                displayName = it.displayName ?: it.userLabel,
                instrumentType = it.instrumentType,
                status = it.status,
            )
        }
        val accounts = accountRepository.getAllOnce().take(MAX_ACCOUNTS).map {
            ContextAccount(
                id = it.id,
                sourceId = it.sourceId,
                instrumentType = it.instrumentType,
                issuer = it.issuer,
                maskedNumber = it.maskedNumber,
                currency = it.currency,
            )
        }
        val merchants = merchantRepository.getRecent(MAX_MERCHANTS).map {
            ContextMerchant(
                id = it.id, name = it.name,
                normalizedName = it.normalizedName, vpa = it.vpa,
            )
        }
        return ResolutionContext(
            knownSources = sources,
            knownAccounts = accounts,
            knownMerchants = merchants,
        )
    }

    private suspend fun materialiseSource(source: SourceChoice, now: Long): Long = when (source) {
        is SourceChoice.Existing -> source.sourceId
        is SourceChoice.New -> {
            sourceRepository.findByKey(source.sourceKey)?.id
                ?: sourceRepository.upsert(
                    FinancialSource(
                        sourceKey = source.sourceKey,
                        deducedType = source.deducedType,
                        userLabel = source.suggestedDisplayName,
                        firstSeenTimestamp = now,
                        displayName = source.suggestedDisplayName,
                        bankName = source.suggestedBankName,
                        instrumentType = source.suggestedInstrumentType,
                        status = SourceStatus.CONFIRMED.name,
                        confirmedAt = now,
                    )
                )
        }
    }

    private suspend fun materialiseAccount(sourceId: Long, account: AccountChoice, now: Long): Long =
        when (account) {
            is AccountChoice.Existing -> account.accountId
            is AccountChoice.New -> {
                accountRepository.findBySourceAndMasked(sourceId, account.maskedNumber)?.id
                    ?: accountRepository.insert(
                        Account(
                            sourceId = sourceId,
                            instrumentType = account.instrumentType,
                            issuer = account.issuer,
                            maskedNumber = account.maskedNumber,
                            currency = account.currency,
                            createdAt = now,
                        )
                    )
            }
        }

    /**
     * Returns the [Merchant] row that was materialised (or the
     * existing one). The caller reads `.id` to set on the
     * transaction. Returns null when the LLM said "no merchant"
     * (P2P transfer without a recognisable counterparty).
     *
     * If the LLM also emitted a category, this method updates the
     * merchant's `categoryId` — but only when the merchant is
     * freshly created or its existing category is null. Existing
     * categorised merchants keep their category across
     * re-categorisations.
     */
    private suspend fun materialiseMerchant(
        merchant: MerchantChoice,
        contract: A2Contract,
        now: Long,
    ): Merchant? = when (merchant) {
        is MerchantChoice.Existing -> {
            val row = merchantRepository.getById(merchant.merchantId) ?: return null
            if (row.categoryId == null) {
                val cat = materialiseCategory(contract, now) ?: return row
                merchantRepository.update(row.copy(categoryId = cat.id))
                row.copy(categoryId = cat.id)
            } else row
        }
        is MerchantChoice.New -> {
            val localNormalized = MerchantNormalizer.normalize(merchant.name)
                .ifEmpty { merchant.normalizedName }
            val modelNormalized = merchant.normalizedName
            val category = materialiseCategory(contract, now)
            val existing = merchantRepository.findByNormalizedName(localNormalized)
                ?: merchantRepository.findByNormalizedName(modelNormalized)
                ?: merchantRepository.findByVpa(merchant.vpa ?: "")
            if (existing != null) {
                if (existing.categoryId == null && category != null) {
                    merchantRepository.update(existing.copy(categoryId = category.id))
                    existing.copy(categoryId = category.id)
                } else existing
            } else {
                val newId = merchantRepository.insert(
                    Merchant(
                        name = merchant.name,
                        normalizedName = localNormalized,
                        vpa = merchant.vpa,
                        categoryId = category?.id,
                        firstSeenAt = now,
                    )
                )
                merchantRepository.getById(newId)
            }
        }
        is MerchantChoice.None -> null
    }

    /**
     * Look up or create the [Category] from the contract's
     * `categoryName`. Returns null when the LLM did not emit a
     * categoryName (i.e. "I am not confident enough").
     */
    private suspend fun materialiseCategory(contract: A2Contract, now: Long): Category? {
        val name = contract.categoryName?.takeIf { it.isNotBlank() } ?: return null
        return categoryRepository.getOrCreate(name, contract.categoryEmoji, now)
    }

    private suspend fun insertTransaction(txn: Transaction): Long =
        transactionRepository.insert(txn)

    private fun buildTransaction(
        parsed: ParsedSms,
        accountId: Long,
        merchantId: Long?,
        categoryId: Long?,
        contractTitle: String?,
        smsTimestampMillis: Long,
        a2Confidence: Float,
        now: Long,
    ): Transaction {
        val amountPaise = parsed.amountPaise ?: 0L
        val direction = when (parsed.direction) {
            TransactionDirection.CREDIT.name -> TransactionDirection.CREDIT
            else -> TransactionDirection.DEBIT
        }
        val currency = parsed.currency ?: "INR"
        // Per the v0.6 day-grouping fix, the transaction timestamp is
        // the SMS receive timestamp. The model's `parsed.txnAtMillis`
        // is preserved on `parsed_sms` for the audit log but not on
        // this row.
        val txnAtMillis = smsTimestampMillis
        val title = contractTitle?.takeIf { it.isNotBlank() }
        return Transaction(
            accountId = accountId,
            merchantId = merchantId,
            rawSmsId = parsed.rawSmsId,
            parsedSmsId = parsed.id,
            amountPaise = amountPaise,
            currency = currency,
            direction = direction.name,
            txnAtMillis = txnAtMillis,
            channel = parsed.channel,
            referenceNo = parsed.referenceNo,
            status = TransactionStatus.CONFIRMED.name,
            confidence = a2Confidence,
            notes = null,
            title = title,
            categoryId = categoryId,
            createdAt = now,
        )
    }
}

/**
 * In-memory bundle of the database slice A2 ships into its prompt.
 * Serialised to a flat JSON string by [toBundleJson] and
 * concatenated with the parsed SMS as the A2 user message.
 */
data class ResolutionContext(
    val knownSources: List<ContextSource> = emptyList(),
    val knownAccounts: List<ContextAccount> = emptyList(),
    val knownMerchants: List<ContextMerchant> = emptyList(),
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
