package com.spendai.app.ui.test

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.spendai.app.SpendAiApp
import com.spendai.app.inference.GemmaInferenceEngine
import com.spendai.app.inference.InferenceConfig
import com.spendai.app.inference.InferenceState
import com.spendai.app.ui.setup.SetupViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The literal prompt we send to the model. It is deliberately strict:
 * the model is told to reply with one specific string and nothing else.
 * This is the surface we grade on the response side.
 */
const val PROBE_PROMPT = "Respond with exactly this string and nothing else: I\u2019m online"

private const val EXPECTED_LOWER = "i\u2019m online"

/**
 * UI state for the test screen.
 *
 * @property phase what we're doing right now.
 * @property response the model reply (streaming or final). May be null.
 * @property pass true iff the response matched the expected probe string.
 * @property engineLabel short human-readable engine state, e.g.
 *   "Ready (GPU)".
 */
data class TestUiState(
    val phase: Phase = Phase.Idle,
    val response: String? = null,
    val pass: Boolean = false,
    val engineLabel: String = "",
) {
    enum class Phase { Idle, Initializing, Asking, Pass, Fail }
}

class TestViewModel(
    application: Application,
    private val setup: SetupViewModel,
) : AndroidViewModel(application) {

    private val app: SpendAiApp
        get() = getApplication<SpendAiApp>()

    private val engine: GemmaInferenceEngine
        get() = app.gemmaInferenceEngine

    private val _ui = MutableStateFlow(TestUiState())
    val ui: StateFlow<TestUiState> = _ui.asStateFlow()

    init {
        // Surface the engine state as a short label so the user can
        // see which backend ended up running.
        viewModelScope.launch {
            engine.state.collect { state ->
                _ui.update { it.copy(engineLabel = labelFor(state)) }
            }
        }
    }

    fun run() {
        if (_ui.value.phase == TestUiState.Phase.Initializing ||
            _ui.value.phase == TestUiState.Phase.Asking
        ) return
        _ui.update {
            it.copy(phase = TestUiState.Phase.Initializing, response = null, pass = false)
        }
        viewModelScope.launch {
            try {
                engine.initialize(getApplication(), InferenceConfig())
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        phase = TestUiState.Phase.Fail,
                        response = "Engine init failed: ${t.message ?: t.javaClass.simpleName}",
                    )
                }
                return@launch
            }

            _ui.update { it.copy(phase = TestUiState.Phase.Asking) }

            val sb = StringBuilder()
            try {
                engine.probeStreaming(PROBE_PROMPT).collect { chunk ->
                    sb.append(chunk)
                    _ui.update { it.copy(response = sb.toString()) }
                }
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        phase = TestUiState.Phase.Fail,
                        response = (sb.toString().ifBlank { "<no output>" }) +
                            "\n[stream error: ${t.message ?: t.javaClass.simpleName}]",
                    )
                }
                return@launch
            }

            val finalText = sb.toString()
            val matched = parsePass(finalText)
            _ui.update {
                it.copy(
                    phase = if (matched) TestUiState.Phase.Pass else TestUiState.Phase.Fail,
                    pass = matched,
                )
            }
            if (matched) {
                setup.setModelProbedOk(true)
            }
        }
    }

    fun continueAnyway() {
        // Allows the user to bypass a failed probe. We persist the
        // "probed ok" flag so the navigation logic still routes to Home.
        viewModelScope.launch { setup.setModelProbedOk(true) }
    }

    private fun labelFor(state: InferenceState): String = when (state) {
        InferenceState.Uninitialized -> "Uninitialized"
        InferenceState.Loading -> "Loading"
        InferenceState.Ready -> "Ready"
        InferenceState.Busy -> "Busy"
        is InferenceState.Error -> "Error: ${state.message}"
    }

    companion object {
        /**
         * Returns true iff [raw] matches the expected "I'm online"
         * probe after trimming, lowercasing, and stripping at most one
         * trailing punctuation/quote character. We keep the matcher
         * intentionally tiny so it is easy to unit-test.
         */
        fun parsePass(raw: String): Boolean {
            val trimmed = raw.trim().lowercase()
            if (trimmed == EXPECTED_LOWER) return true
            // Strip any run of non-alphanumeric characters from BOTH
            // ends. This accepts replies wrapped in quotes / brackets
            // / backticks, or trailed by punctuation, without
            // accepting a reply that contains extra prose.
            val stripped = trimmed.trim { c -> !c.isLetterOrDigit() }
            return stripped == EXPECTED_LOWER
        }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                TestViewModel(app, SetupViewModel(app))
            }
        }
    }
}
