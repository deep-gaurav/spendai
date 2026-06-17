package com.spendai.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spendai.app.SpendAiApp
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.repository.TransactionRepository
import com.spendai.app.domain.ingestion.DateRange
import com.spendai.app.domain.ingestion.IngestionProgress
import com.spendai.app.inference.InferenceState
import com.spendai.app.service.IngestionService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val progress: IngestionProgress = IngestionProgress.Idle,
    val engineState: InferenceState = InferenceState.Uninitialized,
    val engineLabel: String = "Not loaded",
    val recentTransactions: List<Transaction> = emptyList(),
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app: SpendAiApp
        get() = getApplication<SpendAiApp>()

    private val transactions: TransactionRepository = app.transactionRepository

    private val source: Flow<HomeUiState> = combine(
        IngestionService.progress,
        app.gemmaInferenceEngine.state,
        transactions.observeAll(),
    ) { progress, engineState, allTxns ->
        HomeUiState(
            progress = progress,
            engineState = engineState,
            engineLabel = labelFor(engineState),
            recentTransactions = allTxns.take(5),
        )
    }

    val ui: StateFlow<HomeUiState> = source.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    /**
     * Start a foreground ingestion for the user-picked [range]. The
     * service is the only executor; its re-entrancy guard prevents
     * a duplicate run if this fires while another is in flight.
     */
    fun startIngest(range: DateRange) {
        IngestionService.start(getApplication(), range)
    }

    /**
     * Start the "Re-process pending" pipeline. The service re-runs
     * A1+A2 on every `raw_sms` row whose `processedAt` is still
     * null. Covers UNPARSED rows that A1 or A2 failed on.
     */
    fun startReprocess() {
        IngestionService.startPending(getApplication())
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
}

internal fun labelFor(state: InferenceState): String = when (state) {
    is InferenceState.Uninitialized -> "Not loaded"
    is InferenceState.Loading -> "Loading…"
    is InferenceState.Ready -> "Ready on ${state.backendLabel}"
    is InferenceState.Busy -> state.progress.toLabel()
    is InferenceState.Error -> "Error: ${state.message}"
}
