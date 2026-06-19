package com.spendai.app.ui.edit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.spendai.app.SpendAiApp
import com.spendai.app.data.local.entity.Account
import com.spendai.app.data.local.entity.Category
import com.spendai.app.data.local.entity.Merchant
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.local.entity.TransactionDirection
import com.spendai.app.data.local.entity.TransactionStatus
import com.spendai.app.data.repository.AccountRepository
import com.spendai.app.data.repository.CategoryRepository
import com.spendai.app.data.repository.MerchantRepository
import com.spendai.app.data.local.dao.LinkedSmsDao
import com.spendai.app.data.local.dao.LinkedSmsRow
import com.spendai.app.data.repository.ManualCorrectionRepository
import com.spendai.app.data.repository.SmsRepository
import com.spendai.app.data.repository.TransactionRepository
import com.spendai.app.domain.ingestion.IngestionPipeline
import com.spendai.app.domain.model.MerchantNormalizer
import com.spendai.app.domain.ingestion.IngestionProgress
import com.spendai.app.service.IngestionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editable view of a [Transaction] for the manual-edit screen.
 *
 * The user can fix anything A2 got wrong: change the title, change
 * the merchant (existing or new), change the account, change the
 * category, edit the amount, flip the direction, or annotate with a
 * note. Save writes the row back to the DB; delete removes it and
 * the screen pops.
 */
data class EditTransactionState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val transactionId: Long = 0L,
    val amountText: String = "",
    val title: String = "",
    val currency: String = "INR",
    val direction: TransactionDirection = TransactionDirection.DEBIT,
    val channel: String? = null,
    val referenceNo: String = "",
    val notes: String = "",
    val status: String = TransactionStatus.CONFIRMED.name,
    val confidence: Float = 1f,
    val accountId: Long = 0L,
    val merchantId: Long? = null,
    val newMerchantName: String = "",
    val creatingMerchant: Boolean = false,
    val categoryId: Long? = null,
    val allMerchants: List<Merchant> = emptyList(),
    val allAccounts: List<Account> = emptyList(),
    val allCategories: List<Category> = emptyList(),
    val rawSms: RawSmsMessage? = null,
    val linkedSms: List<LinkedSmsRow> = emptyList(),
    val saveError: String? = null,
    val saved: Boolean = false,
    val deleted: Boolean = false,
    val reprompt: RepromptState = RepromptState(),
)

/**
 * UI state for the reprompt flow. Lives on the edit screen so
 * the dialog can disable itself while the pipeline is running and
 * surface a brief summary when it finishes.
 */
data class RepromptState(
    val running: Boolean = false,
    val lastResult: String? = null,
    val lastError: String? = null,
)

class EditTransactionViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    private val merchantRepository: MerchantRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val smsRepository: SmsRepository,
    private val linkedSmsDao: LinkedSmsDao,
    private val manualCorrectionRepository: ManualCorrectionRepository?,
    private val ingestionPipeline: IngestionPipeline?,
    private val gemmaInferenceEngine: com.spendai.app.inference.GemmaInferenceEngine?,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(EditTransactionState())
    val state: StateFlow<EditTransactionState> = _state.asStateFlow()

    init {
        val id: Long = savedStateHandle.get<Long>(ARG_TRANSACTION_ID) ?: 0L
        _state.update { it.copy(transactionId = id) }
        load(id)
    }

    private fun load(transactionId: Long) {
        if (transactionId <= 0L) {
            _state.update { it.copy(loading = false, notFound = true) }
            return
        }
        viewModelScope.launch {
            val txn = withContext(Dispatchers.IO) { transactionRepository.getById(transactionId) }
            if (txn == null) {
                _state.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            val merchants = withContext(Dispatchers.IO) { merchantRepository.observeAll().first() }
            val accounts = withContext(Dispatchers.IO) { accountRepository.observeAll().first() }
            val categories = withContext(Dispatchers.IO) { categoryRepository.observeAll().first() }
            val rawSms = withContext(Dispatchers.IO) { smsRepository.getById(txn.rawSmsId) }
            val linkedSms = withContext(Dispatchers.IO) { loadLinkedSms(txn) }
            _state.update {
                it.copy(
                    loading = false,
                    notFound = false,
                    amountText = formatAmount(txn.amountPaise),
                    title = txn.title.orEmpty(),
                    currency = txn.currency,
                    direction = runCatching { TransactionDirection.valueOf(txn.direction) }
                        .getOrDefault(TransactionDirection.DEBIT),
                    channel = txn.channel,
                    referenceNo = txn.referenceNo ?: "",
                    notes = txn.notes ?: "",
                    status = txn.status,
                    confidence = txn.confidence,
                    accountId = txn.accountId,
                    merchantId = txn.merchantId,
                    categoryId = txn.categoryId,
                    allMerchants = merchants,
                    allAccounts = accounts,
                    allCategories = categories,
                    rawSms = rawSms,
                    linkedSms = linkedSms,
                )
            }
            observeRepromptService(transactionId)
        }
    }

    private suspend fun loadLinkedSms(txn: Transaction): List<LinkedSmsRow> {
        // Source row first so the UI can render it as the first card.
        val source = linkedSmsDao.getSourceSms(txn.rawSmsId, txn.id)
        val linked = linkedSmsDao.getLinkedSms(txn.id)
        val duplicates = linkedSmsDao.getDuplicatesOf(txn.id)
        return listOfNotNull(source) + linked + duplicates
    }

    fun setAmount(text: String) { _state.update { it.copy(amountText = text, saveError = null) } }
    fun setTitle(text: String) { _state.update { it.copy(title = text) } }
    fun setCurrency(text: String) { _state.update { it.copy(currency = text) } }
    fun setDirection(d: TransactionDirection) { _state.update { it.copy(direction = d) } }
    fun setChannel(c: String?) { _state.update { it.copy(channel = c) } }
    fun setReferenceNo(text: String) { _state.update { it.copy(referenceNo = text) } }
    fun setNotes(text: String) { _state.update { it.copy(notes = text) } }
    fun setAccount(id: Long) { _state.update { it.copy(accountId = id) } }
    fun setMerchant(id: Long?) {
        _state.update { it.copy(merchantId = id, creatingMerchant = false, newMerchantName = "") }
    }
    fun startCreatingMerchant() {
        _state.update { it.copy(creatingMerchant = true, merchantId = null) }
    }
    fun setNewMerchantName(name: String) { _state.update { it.copy(newMerchantName = name) } }
    fun setCategory(id: Long?) { _state.update { it.copy(categoryId = id) } }

    /**
     * Create a new [Category] from a freeform name and emoji
     * (chosen by the user from the category picker dialog). The
     * new id is committed as the transaction's `categoryId`.
     */
    fun addCategory(name: String, emoji: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val cat = withContext(Dispatchers.IO) {
                categoryRepository.getOrCreate(trimmed, emoji, System.currentTimeMillis())
            }
            // Refresh the local list so the picker shows the new row.
            val categories = withContext(Dispatchers.IO) { categoryRepository.getAllOnce() }
            _state.update { it.copy(categoryId = cat.id, allCategories = categories) }
        }
    }

    fun save() {
        val s = _state.value
        if (s.accountId <= 0L) {
            _state.update { it.copy(saveError = "Pick an account") }
            return
        }
        val paise = parseAmount(s.amountText)
        if (paise == null || paise <= 0L) {
            _state.update { it.copy(saveError = "Amount must be > 0") }
            return
        }
        viewModelScope.launch {
            val merchantId: Long? = withContext(Dispatchers.IO) { resolveMerchantId(s) }
            val categoryId: Long? = withContext(Dispatchers.IO) { resolveCategoryId(s) }
            val current = withContext(Dispatchers.IO) { transactionRepository.getById(s.transactionId) }
                ?: return@launch
            val updated = current.copy(
                accountId = s.accountId,
                merchantId = merchantId,
                categoryId = categoryId,
                amountPaise = paise,
                currency = s.currency.ifBlank { "INR" },
                direction = s.direction.name,
                channel = s.channel,
                referenceNo = s.referenceNo.ifBlank { null },
                notes = s.notes.ifBlank { null },
                title = s.title.ifBlank { null },
            )
            withContext(Dispatchers.IO) { transactionRepository.update(updated) }
            // Mirror the new categoryId onto the merchant so future
            // transactions with the same merchant inherit it.
            if (merchantId != null && categoryId != null) {
                withContext(Dispatchers.IO) {
                    merchantRepository.getById(merchantId)?.let { m ->
                        if (m.categoryId == null) {
                            merchantRepository.update(m.copy(categoryId = categoryId))
                        }
                    }
                }
            }
            _state.update { it.copy(saved = true, saveError = null) }
        }
    }

    /**
     * Hand the A3 reprompt off to the foreground
     * [com.spendai.app.service.IngestionService]. The service owns
     * the wake lock, the persistent notification, the retry
     * policy, and the durable [com.spendai.app.data.local.entity.RepromptJob]
     * row; this function only validates the prompt, kicks the
     * service, and updates the local [RepromptState] from the
     * service's [IngestionService.repromptProgress] flow.
     *
     * Returning early does NOT cancel the run — once the service
     * has the intent, the pipeline is in flight. The user cancels
     * via [cancelReprompt].
     */
    fun reprompt(userPrompt: String) {
        val s = _state.value
        val prompt = userPrompt.trim()
        if (prompt.isEmpty()) {
            _state.update { it.copy(reprompt = RepromptState(lastError = "Prompt is empty")) }
            return
        }
        if (s.transactionId <= 0L || s.rawSms == null) {
            _state.update { it.copy(reprompt = RepromptState(lastError = "Transaction not loaded")) }
            return
        }
        // Collect every raw_sms id we are about to re-run on. The
        // source is the one the user opened; the duplicates and
        // linked rows are the other SMSes the user grouped with it.
        val ids = buildList {
            add(s.rawSms.id)
            s.linkedSms.forEach { row ->
                // Skip the source row (already added) and any rows we
                // already pulled in to keep the override focused.
                if (row.id != s.rawSms.id) add(row.id)
            }
        }
        if (ids.isEmpty()) {
            _state.update { it.copy(reprompt = RepromptState(lastError = "No SMSes to reprompt")) }
            return
        }
        // Hand off to the service. The service starts the foreground
        // notification, persists a RepromptJob, runs the pipeline,
        // and posts the terminal notification with a deep-link back
        // to this screen.
        IngestionService.startReprompt(
            context = getApplication(),
            rawSmsIds = ids,
            userPrompt = prompt,
            transactionId = s.transactionId.takeIf { it > 0L },
        )
        // Optimistically flip the local state so the button
        // disables and the dialog can close. The service flow
        // observer below will refine running / lastResult /
        // lastError as the run progresses.
        _state.update { it.copy(reprompt = RepromptState(running = true)) }
    }

    /**
     * Send an [IngestionService.ACTION_CANCEL] intent so the user
     * can stop a reprompt they kicked off earlier. The service
     * marks the job FAILED with "Cancelled" and posts the
     * terminal notification; the service flow observer picks up
     * the transition and updates the local state.
     */
    fun cancelReprompt() {
        IngestionService.cancel(getApplication())
    }

    /**
     * Reset the reprompt status so the dialog can be reopened
     * cleanly. Does NOT cancel a running reprompt — the service is
     * the owner.
     */
    fun clearRepromptStatus() {
        _state.update { it.copy(reprompt = RepromptState()) }
    }

    /**
     * Observe the service's reprompt StateFlows. The first time a
     * transaction id is set on the state, this launches a
     * long-lived collector that:
     *  1. Translates [IngestionService.repromptProgress] events
     *     into local [RepromptState] updates (running / lastResult
     *     / lastError).
     *  2. Watches [IngestionService.repromptJobsByTransactionId]
     *     to know if a reprompt is active for the open
     *     transaction, so the screen can show a banner even if the
     *     user navigates back mid-run.
     *
     * Called from [load] after the transaction is fetched. The
     * collector is scoped to the [viewModelScope] so it stops
     * collecting when the ViewModel is cleared (the service still
     * owns the actual run).
     */
    private fun observeRepromptService(transactionId: Long) {
        viewModelScope.launch {
            IngestionService.repromptProgress.collect { progress ->
                val newState = when (progress) {
                    IngestionProgress.Idle -> RepromptState(
                        running = _state.value.reprompt.running,
                        lastResult = _state.value.reprompt.lastResult,
                        lastError = _state.value.reprompt.lastError,
                    )
                    is IngestionProgress.EngineInitialising ->
                        RepromptState(running = true)
                    is IngestionProgress.LoadingFromSource ->
                        RepromptState(running = true)
                    is IngestionProgress.MessageParsed ->
                        RepromptState(running = true)
                    is IngestionProgress.MessageCommitted ->
                        RepromptState(running = true)
                    is IngestionProgress.MessageSkipped ->
                        RepromptState(running = true)
                    is IngestionProgress.Done -> {
                        // The Done event from the pipeline only carries
                        // the in-pipeline summary. The service posts a
                        // separate terminal notification with the
                        // deep-link; the screen re-loads the transaction
                        // to reflect the new state.
                        val txn = withContext(Dispatchers.IO) {
                            transactionRepository.getById(transactionId)
                        }
                        if (txn != null) {
                            val linkedSms = withContext(Dispatchers.IO) { loadLinkedSms(txn) }
                            _state.update { it.copy(linkedSms = linkedSms) }
                        }
                        RepromptState(
                            running = false,
                            lastResult = "Reprompt done",
                        )
                    }
                    is IngestionProgress.Failure ->
                        RepromptState(
                            running = false,
                            lastError = progress.message,
                        )
                    IngestionProgress.Cancelled ->
                        RepromptState(
                            running = false,
                            lastError = "Cancelled",
                        )
                }
                _state.update { it.copy(reprompt = newState) }
            }
        }
        viewModelScope.launch {
            IngestionService.repromptJobsByTransactionId.collect { byTxnId ->
                val active = byTxnId[transactionId]
                if (active != null) {
                    _state.update { it.copy(reprompt = it.reprompt.copy(running = true)) }
                }
            }
        }
    }

    fun delete() {
        val id = _state.value.transactionId
        if (id <= 0L) return
        viewModelScope.launch {
            val current = withContext(Dispatchers.IO) { transactionRepository.getById(id) } ?: return@launch
            withContext(Dispatchers.IO) { transactionRepository.delete(current) }
            _state.update { it.copy(deleted = true) }
        }
    }

    private suspend fun resolveMerchantId(s: EditTransactionState): Long? {
        if (s.creatingMerchant) {
            val name = s.newMerchantName.trim()
            if (name.isEmpty()) return null
            val normalized = MerchantNormalizer.normalize(name)
            val existing = if (normalized.isNotEmpty()) merchantRepository.findByNormalizedName(normalized) else null
            if (existing != null) return existing.id
            val row = Merchant(
                name = name,
                normalizedName = normalized.ifEmpty { name.lowercase() },
                firstSeenAt = System.currentTimeMillis(),
            )
            return merchantRepository.insert(row)
        }
        return s.merchantId
    }

    private suspend fun resolveCategoryId(s: EditTransactionState): Long? = s.categoryId

    companion object {
        const val ARG_TRANSACTION_ID = "transactionId"

        fun factory(transactionId: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                val app = application as SpendAiApp
                val handle = extras.createSavedStateHandle().apply {
                    set(ARG_TRANSACTION_ID, transactionId)
                }
                return EditTransactionViewModel(
                    application = application,
                    savedStateHandle = handle,
                    transactionRepository = app.transactionRepository,
                    merchantRepository = app.merchantRepository,
                    accountRepository = app.accountRepository,
                    categoryRepository = app.categoryRepository,
                    smsRepository = app.smsRepository,
                    linkedSmsDao = app.database.linkedSmsDao(),
                    manualCorrectionRepository = app.manualCorrectionRepository,
                    ingestionPipeline = app.ingestionPipeline,
                    gemmaInferenceEngine = app.gemmaInferenceEngine,
                ) as T
            }
        }

        private fun formatAmount(paise: Long): String {
            val rupees = paise / 100
            val p = (paise % 100).toString().padStart(2, '0')
            return "$rupees.$p"
        }

        private fun parseAmount(text: String): Long? {
            val cleaned = text.replace(",", "").trim()
            if (cleaned.isEmpty()) return null
            val parts = cleaned.split('.')
            return try {
                val whole = parts[0].toLong()
                val fraction = if (parts.size > 1) {
                    val p = parts[1].padEnd(2, '0').take(2)
                    p.toLong()
                } else 0L
                if (whole < 0L) null else whole * 100 + fraction
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}
