package com.spendai.app.ui.insights

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spendai.app.SpendAiApp
import com.spendai.app.data.repository.InsightsRepository
import com.spendai.app.domain.insights.InsightsSnapshot
import com.spendai.app.domain.insights.InsightsWindow
import com.spendai.app.domain.insights.InsightsWindowCalculator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId

/**
 * UI state for the Insights screen. The snapshot is wrapped
 * in a state so a "first paint" can show a neutral empty
 * snapshot while the Room flows are still resolving.
 */
data class InsightsUiState(
    val window: InsightsWindow = InsightsWindow.THIS_MONTH,
    val snapshot: InsightsSnapshot? = null,
)

/**
 * ViewModel for the auto insights screen. Holds a single
 * [MutableStateFlow] of the selected [InsightsWindow] and
 * derives the snapshot via [flatMapLatest] on the repository
 * — this is the standard combine+stateIn pattern used by
 * the rest of the app, and it ensures the active window
 * change tears down the previous repository subscription.
 *
 * The constructor takes only [Application] so the default
 * `viewModel()` factory can instantiate it. Time and zone
 * are read inline; tests that need to pin time should drive
 * the ViewModel through the repository directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModel(application: Application) : AndroidViewModel(application) {

    private val app: SpendAiApp
        get() = getApplication()

    private val repo: InsightsRepository = app.insightsRepository

    private val windowFlow = MutableStateFlow(InsightsWindow.THIS_MONTH)

    private val snapshotFlow = windowFlow.flatMapLatest { window ->
        repo.snapshot(
            window = window,
            now = System.currentTimeMillis(),
            zone = ZoneId.systemDefault(),
        ).map { snapshot -> window to snapshot }
    }

    val window: StateFlow<InsightsWindow> = windowFlow.asStateFlow()

    val ui: StateFlow<InsightsUiState> = snapshotFlow
        .map { (window, snapshot) -> InsightsUiState(window = window, snapshot = snapshot) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = InsightsUiState(),
        )

    fun setWindow(window: InsightsWindow) {
        if (windowFlow.value != window) {
            windowFlow.value = window
        }
    }

    /** Re-emit the current window with the latest clock. */
    fun refresh() {
        val current = windowFlow.value
        windowFlow.value = current
    }

    /** Pre-boundary helper, exposed for tests. */
    internal fun boundariesFor(window: InsightsWindow) =
        InsightsWindowCalculator.boundaries(window, System.currentTimeMillis(), ZoneId.systemDefault())
}
