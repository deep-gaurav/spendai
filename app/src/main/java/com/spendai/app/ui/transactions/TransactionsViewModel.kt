package com.spendai.app.ui.transactions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spendai.app.SpendAiApp
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

data class TransactionsUiState(
    val grouped: List<DayGroup> = emptyList(),
)

data class DayGroup(
    val day: LocalDate,
    val transactions: List<Transaction>,
)

class TransactionsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo: TransactionRepository =
        getApplication<SpendAiApp>().transactionRepository

    private val zone: ZoneId = ZoneId.systemDefault()

    private val source: Flow<TransactionsUiState> = repo.observeAll()
        .map { txns ->
            val grouped: List<DayGroup> = txns.groupBy { row ->
                java.time.Instant.ofEpochMilli(row.txnAtMillis)
                    .atZone(zone)
                    .toLocalDate()
            }.toSortedMap(compareByDescending { it })
                .map { (day, list) -> DayGroup(day, list) }
            TransactionsUiState(grouped)
        }

    val ui: StateFlow<TransactionsUiState> = source.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TransactionsUiState(),
    )
}
