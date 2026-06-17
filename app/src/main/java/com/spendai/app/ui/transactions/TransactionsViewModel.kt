package com.spendai.app.ui.transactions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spendai.app.SpendAiApp
import com.spendai.app.data.local.dao.TransactionDetailsRow
import com.spendai.app.data.repository.TransactionRepository
import com.spendai.app.domain.model.TransactionTitle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Display row for the transactions list. Combines the joined
 * [TransactionDetailsRow] with a render-time title.
 */
data class TransactionListItem(
    val details: TransactionDetailsRow,
    val title: String,
    val timeText: String,
    val accountShort: String,
)

data class DayGroup(
    val day: LocalDate,
    val items: List<TransactionListItem>,
)

data class TransactionsUiState(
    val grouped: List<DayGroup> = emptyList(),
)

class TransactionsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo: TransactionRepository =
        getApplication<SpendAiApp>().transactionRepository

    private val zone: ZoneId = ZoneId.systemDefault()

    private val source: Flow<TransactionsUiState> = repo.observeAllWithDetails()
        .map { rows ->
            val grouped: List<DayGroup> = rows
                .map { row -> row.toListItem(zone) }
                .groupBy { item ->
                    Instant.ofEpochMilli(item.details.txnAtMillis)
                        .atZone(zone)
                        .toLocalDate()
                }
                .toSortedMap(compareByDescending { it })
                .map { (day, items) -> DayGroup(day, items) }
            TransactionsUiState(grouped)
        }

    val ui: StateFlow<TransactionsUiState> = source.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TransactionsUiState(),
    )
}

private fun TransactionDetailsRow.toListItem(zone: ZoneId): TransactionListItem {
    val direction = runCatching {
        com.spendai.app.data.local.entity.TransactionDirection.valueOf(direction)
    }.getOrDefault(com.spendai.app.data.local.entity.TransactionDirection.DEBIT)
    val title = title?.takeIf { it.isNotBlank() }
        ?: TransactionTitle.derive(merchantName, categoryName, direction, channel)
    val time = Instant.ofEpochMilli(txnAtMillis).atZone(zone).toLocalTime()
    val timeText = "%02d:%02d".format(time.hour, time.minute)
    val accountShort = buildString {
        if (!accountIssuer.isNullOrBlank()) append(accountIssuer)
        if (!accountMaskedNumber.isNullOrBlank()) {
            if (isNotEmpty()) append(' ')
            append("\u2022\u2022")
            append(accountMaskedNumber.takeLast(4))
        }
        if (isEmpty()) append("Account")
    }
    return TransactionListItem(
        details = this,
        title = title,
        timeText = timeText,
        accountShort = accountShort,
    )
}
