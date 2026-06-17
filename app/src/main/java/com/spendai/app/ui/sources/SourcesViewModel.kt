package com.spendai.app.ui.sources

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spendai.app.SpendAiApp
import com.spendai.app.data.local.entity.Account
import com.spendai.app.data.local.entity.Category
import com.spendai.app.data.local.entity.FinancialSource
import com.spendai.app.data.repository.AccountRepository
import com.spendai.app.data.repository.CategoryRepository
import com.spendai.app.data.repository.FinancialSourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val ACCOUNT_COLOR_PALETTE: List<String> = listOf(
    "#FF6B6B", "#4ECDC4", "#45B7D1", "#96CEB4", "#FFEAA7", "#DFE6E9",
    "#A29BFE", "#FD79A8", "#FDCB6E", "#6C5CE7", "#00B894", "#E17055",
)

data class SourcesUiState(
    val sources: List<FinancialSource> = emptyList(),
    val accounts: Map<Long, List<Account>> = emptyMap(),
    val categories: List<Category> = emptyList(),
)

class SourcesViewModel(application: Application) : AndroidViewModel(application) {

    private val app: SpendAiApp
        get() = getApplication<SpendAiApp>()

    private val sourceRepo: FinancialSourceRepository = app.financialSourceRepository
    private val accountRepo: AccountRepository = app.accountRepository
    private val categoryRepo: CategoryRepository = app.categoryRepository

    private val _state = MutableStateFlow(SourcesUiState())
    val state: StateFlow<SourcesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val sources = withContext(Dispatchers.IO) { sourceRepo.allOnce() }
            val accounts = withContext(Dispatchers.IO) {
                accountRepo.getAllOnce().groupBy { it.sourceId }
            }
            _state.value = _state.value.copy(sources = sources, accounts = accounts)
        }
        viewModelScope.launch {
            accountRepo.observeAll().collect { accounts ->
                _state.value = _state.value.copy(accounts = accounts.groupBy { it.sourceId })
            }
        }
        viewModelScope.launch {
            categoryRepo.observeAll().collect { categories ->
                _state.value = _state.value.copy(categories = categories)
            }
        }
    }

    fun setSourceDisplayName(source: FinancialSource, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            sourceRepo.upsert(source.copy(displayName = displayName.ifBlank { null }))
        }
    }

    fun setAccountColor(account: Account, colorHex: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            accountRepo.update(account.copy(colorHex = colorHex))
        }
    }

    fun setCategoryEmoji(category: Category, emoji: String) {
        viewModelScope.launch(Dispatchers.IO) {
            categoryRepo.updateEmoji(category.id, emoji)
        }
    }
}
