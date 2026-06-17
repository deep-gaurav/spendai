package com.spendai.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spendai.app.SpendAiApp
import com.spendai.app.data.local.entity.PendingReviewKind
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.repository.PendingReviewRepository
import com.spendai.app.data.repository.TransactionRepository
import com.spendai.app.domain.ingestion.DateRange
import com.spendai.app.domain.ingestion.IngestionProgress
import com.spendai.app.inference.InferenceState
import com.spendai.app.service.IngestionService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val progress: IngestionProgress = IngestionProgress.Idle,
    val engineState: InferenceState = InferenceState.Uninitialized,
    val engineLabel: String = "Not loaded",
    val pendingSourceCount: Int = 0,
    val pendingTxnCount: Int = 0,
    val recentTransactions: List<Transaction> = emptyList(),
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app: SpendAiApp
        get() = getApplication<SpendAiApp>()

    private val transactions: TransactionRepository = app.transactionRepository
    private val reviews: PendingReviewRepository = app.pendingReviewRepository

    private val source: Flow<HomeUiState> = combine(
        IngestionService.progress,
        app.gemmaInferenceEngine.state,
        reviews.observeOpen(PendingReviewKind.SOURCE.name).map { it.size },
        reviews.observeOpen(PendingReviewKind.TRANSACTION.name).map { it.size },
        transactions.observeAll(),
    ) { progress, engineState, sourceCount, txnCount, allTxns ->
        HomeUiState(
            progress = progress,
            engineState = engineState,
            engineLabel = labelFor(engineState),
            pendingSourceCount = sourceCount,
            pendingTxnCount = txnCount,
            recentTransactions = allTxns.take(5),
        )
    }

    val ui: StateFlow<HomeUiState> = source.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    fun startIngest(range: DateRange) {
        IngestionService.start(getApplication(), range)
    }

    fun cancelIngest() {
        IngestionService.cancel(getApplication())
    }

    /**
     * Kick off engine init proactively if it isn't already READY.
     * Called from the home so the user sees "Engine: Loading..."
     * before they tap the Ingest CTA — the 10s model load is
     * otherwise invisible.
     */
    fun warmUpEngine() {
        val engine = app.gemmaInferenceEngine
        if (engine.state.value !is InferenceState.Ready) {
            viewModelScope.launch {
                runCatching { engine.initialize(getApplication()) }
                    .onFailure { /* surfaced via the StateFlow */ }
            }
        }
    }

    fun rangeFor(preset: RangePreset, now: Long = System.currentTimeMillis()): DateRange =
        when (preset) {
            RangePreset.YESTERDAY -> DateRange.calendarDaysBack(now, daysBack = 1)
            RangePreset.LAST_7_DAYS -> DateRange.calendarDaysBack(now, daysBack = 7)
            RangePreset.LAST_30_DAYS -> DateRange.calendarDaysBack(now, daysBack = 30)
        }

    enum class RangePreset { YESTERDAY, LAST_7_DAYS, LAST_30_DAYS }
}

internal fun labelFor(state: InferenceState): String = when (state) {
    is InferenceState.Uninitialized -> "Not loaded"
    is InferenceState.Loading -> "Loading…"
    is InferenceState.Ready -> "Ready on ${state.backendLabel}"
    is InferenceState.Busy -> state.progress.toLabel()
    is InferenceState.Error -> "Error: ${state.message}"
}
