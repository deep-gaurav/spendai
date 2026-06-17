package com.spendai.app.ui.edit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.spendai.app.SpendAiApp
import com.spendai.app.data.local.entity.Account
import com.spendai.app.data.local.entity.Merchant
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.local.entity.TransactionDirection
import com.spendai.app.data.local.entity.TransactionStatus
import com.spendai.app.data.repository.AccountRepository
import com.spendai.app.data.repository.MerchantRepository
import com.spendai.app.data.repository.TransactionRepository
import com.spendai.app.domain.model.MerchantNormalizer
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
 * The user can fix anything A2 got wrong: change the merchant
 * (existing or new), change the account, edit the amount, flip the
 * direction, or annotate with a note. Save writes the row back to
 * the DB; delete removes it and the screen pops.
 */
data class EditTransactionState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val transactionId: Long = 0L,
    val amountText: String = "",
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
    val allMerchants: List<Merchant> = emptyList(),
    val allAccounts: List<Account> = emptyList(),
    val saveError: String? = null,
    val saved: Boolean = false,
    val deleted: Boolean = false,
)

class EditTransactionViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    private val merchantRepository: MerchantRepository,
    private val accountRepository: AccountRepository,
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
            _state.update {
                it.copy(
                    loading = false,
                    notFound = false,
                    amountText = formatAmount(txn.amountPaise),
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
                    allMerchants = merchants,
                    allAccounts = accounts,
                )
            }
        }
    }

    fun setAmount(text: String) { _state.update { it.copy(amountText = text, saveError = null) } }
    fun setCurrency(text: String) { _state.update { it.copy(currency = text) } }
    fun setDirection(d: TransactionDirection) { _state.update { it.copy(direction = d) } }
    fun setChannel(c: String?) { _state.update { it.copy(channel = c) } }
    fun setReferenceNo(text: String) { _state.update { it.copy(referenceNo = text) } }
    fun setNotes(text: String) { _state.update { it.copy(notes = text) } }
    fun setAccount(id: Long) { _state.update { it.copy(accountId = id) } }
    fun setMerchant(id: Long?) { _state.update { it.copy(merchantId = id, creatingMerchant = false, newMerchantName = "") } }
    fun startCreatingMerchant() { _state.update { it.copy(creatingMerchant = true, merchantId = null) } }
    fun setNewMerchantName(name: String) { _state.update { it.copy(newMerchantName = name) } }

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
            val merchantId: Long? = withContext(Dispatchers.IO) {
                resolveMerchantId(s)
            }
            val current = withContext(Dispatchers.IO) { transactionRepository.getById(s.transactionId) }
                ?: return@launch
            val updated = current.copy(
                accountId = s.accountId,
                merchantId = merchantId,
                amountPaise = paise,
                currency = s.currency.ifBlank { "INR" },
                direction = s.direction.name,
                channel = s.channel,
                referenceNo = s.referenceNo.ifBlank { null },
                notes = s.notes.ifBlank { null },
            )
            withContext(Dispatchers.IO) { transactionRepository.update(updated) }
            _state.update { it.copy(saved = true, saveError = null) }
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
