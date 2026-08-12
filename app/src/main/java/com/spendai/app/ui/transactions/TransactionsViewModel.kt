package com.spendai.app.ui.transactions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spendai.app.SpendAiApp
import com.spendai.app.data.repository.TransactionRepository
import com.spendai.app.domain.model.TransactionListItem
import com.spendai.app.domain.model.toListItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DayGroup(
    val day: LocalDate,
    val items: List<TransactionListItem>,
)

/**
 * `hasAnyTransactions` distinguishes "no transactions at all" from
 * "no transactions match the current search" so the screen can
 * show the right empty-state copy.
 */
data class TransactionsUiState(
    val grouped: List<DayGroup> = emptyList(),
    val query: String = "",
    val hasAnyTransactions: Boolean = false,
)

class TransactionsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo: TransactionRepository =
        getApplication<SpendAiApp>().transactionRepository

    private val zone: ZoneId = ZoneId.systemDefault()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val items: Flow<List<TransactionListItem>> = repo.observeAllWithDetails()
        .map { rows -> rows.map { row -> row.toListItem(zone) } }

    val ui: StateFlow<TransactionsUiState> = combine(items, _query) { all, query ->
        val filtered = filterItems(all, query)
        val grouped: List<DayGroup> = filtered
            .groupBy { item ->
                Instant.ofEpochMilli(item.details.txnAtMillis)
                    .atZone(zone)
                    .toLocalDate()
            }
            .toSortedMap(compareByDescending { it })
            .map { (day, dayItems) -> DayGroup(day, dayItems) }
        TransactionsUiState(grouped = grouped, query = query, hasAnyTransactions = all.isNotEmpty())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TransactionsUiState(),
    )

    fun onQueryChange(text: String) {
        _query.value = text
    }

    /**
     * Local (in-memory, on-device) search across everything the
     * row already renders: title, merchant, category, account
     * label, notes, reference number, channel, and the rupee
     * amount. No network call and no new DB query — the screen
     * already holds every transaction via [repo.observeAllWithDetails].
     */
    private fun filterItems(items: List<TransactionListItem>, query: String): List<TransactionListItem> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return items
        return items.filter { item ->
            item.title.lowercase().contains(needle) ||
                item.details.merchantName?.lowercase()?.contains(needle) == true ||
                item.details.categoryName?.lowercase()?.contains(needle) == true ||
                item.accountShort.lowercase().contains(needle) ||
                item.details.notes?.lowercase()?.contains(needle) == true ||
                item.details.referenceNo?.lowercase()?.contains(needle) == true ||
                item.details.channel?.lowercase()?.contains(needle) == true ||
                (item.details.amountPaise / 100).toString().contains(needle)
        }
    }
}
