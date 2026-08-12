package com.spendai.app.ui.tracking

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spendai.app.SpendAiApp
import com.spendai.app.data.local.entity.MonthlySnapshot
import com.spendai.app.data.local.entity.TransactionDirection
import com.spendai.app.data.repository.MonthlySnapshotRepository
import com.spendai.app.data.repository.TransactionRepository
import com.spendai.app.domain.model.TransactionListItem
import com.spendai.app.domain.model.toListItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Per-currency totals for one calendar month. Usually a single entry. */
data class TrackingMonthTotal(
    val currency: String,
    val debitPaise: Long,
    val creditPaise: Long,
    val txnCount: Int,
)

data class TrackingMonthGroup(
    val yearMonth: YearMonth,
    val totals: List<TrackingMonthTotal>,
    val txnCount: Int,
    val items: List<TransactionListItem>,
)

data class TrackingUiState(
    val months: List<TrackingMonthGroup> = emptyList(),
)

/**
 * Groups every transaction by calendar month (most recent first)
 * for the Tracking screen, and — as a side effect of every
 * recompute — write-through backs up each month's totals into
 * the local `monthly_snapshot` table via [MonthlySnapshotRepository].
 * That backup keeps running for as long as this ViewModel is
 * alive (i.e. the Tracking screen has been visited this session),
 * independent of whether the UI is actively collecting [ui].
 */
class TrackingViewModel(application: Application) : AndroidViewModel(application) {

    private val transactionRepo: TransactionRepository =
        getApplication<SpendAiApp>().transactionRepository
    private val snapshotRepo: MonthlySnapshotRepository =
        getApplication<SpendAiApp>().monthlySnapshotRepository

    private val zone: ZoneId = ZoneId.systemDefault()

    private val groupedFlow: Flow<List<TrackingMonthGroup>> = transactionRepo.observeAllWithDetails()
        .map { rows ->
            rows.map { it.toListItem(zone) }
                .groupBy { item ->
                    YearMonth.from(
                        Instant.ofEpochMilli(item.details.txnAtMillis).atZone(zone).toLocalDate(),
                    )
                }
                .entries
                .sortedByDescending { it.key }
                .map { (ym, monthItems) ->
                    val sortedItems = monthItems.sortedByDescending { it.details.txnAtMillis }
                    val totals = monthItems.groupBy { it.details.currency }
                        .map { (currency, currencyItems) ->
                            TrackingMonthTotal(
                                currency = currency,
                                debitPaise = currencyItems
                                    .filter { it.details.direction == TransactionDirection.DEBIT.name }
                                    .sumOf { it.details.amountPaise },
                                creditPaise = currencyItems
                                    .filter { it.details.direction == TransactionDirection.CREDIT.name }
                                    .sumOf { it.details.amountPaise },
                                txnCount = currencyItems.size,
                            )
                        }
                    TrackingMonthGroup(
                        yearMonth = ym,
                        totals = totals,
                        txnCount = monthItems.size,
                        items = sortedItems,
                    )
                }
        }

    val ui: StateFlow<TrackingUiState> = groupedFlow
        .map { TrackingUiState(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TrackingUiState(),
        )

    init {
        viewModelScope.launch {
            groupedFlow.collectLatest { groups -> backUpSnapshots(groups) }
        }
    }

    private suspend fun backUpSnapshots(groups: List<TrackingMonthGroup>) {
        if (groups.isEmpty()) return
        val now = System.currentTimeMillis()
        val rows = groups.flatMap { group ->
            group.totals.map { total ->
                MonthlySnapshot(
                    yearMonth = group.yearMonth.toString(),
                    currency = total.currency,
                    totalDebitPaise = total.debitPaise,
                    totalCreditPaise = total.creditPaise,
                    txnCount = total.txnCount,
                    firstTxnAtMillis = group.items.minOfOrNull { it.details.txnAtMillis },
                    lastTxnAtMillis = group.items.maxOfOrNull { it.details.txnAtMillis },
                    updatedAt = now,
                )
            }
        }
        snapshotRepo.upsertAll(rows)
    }
}
