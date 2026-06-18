package com.spendai.app.ui.download

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.spendai.app.ui.setup.SetupViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class VerificationStatus { IDLE, VERIFYING, SUCCESS, FAILURE }

/**
 * UI state for the download screen.
 */
data class DownloadUiState(
    val present: Boolean = false,
    val running: Boolean = false,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val done: Boolean = false,
    val error: String? = null,
    val apiKey: String = "",
    val provider: String = "GEMINI",
    val model: String = "gemma-4-31b-it",
    val baseUrl: String = "",
    val verificationStatus: VerificationStatus = VerificationStatus.IDLE,
    val verificationError: String? = null,
)

class DownloadViewModel(
    application: Application,
    private val setup: SetupViewModel,
) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(DownloadUiState())
    val ui: StateFlow<DownloadUiState> = _ui.asStateFlow()

    init {
        refreshPresent()
    }

    private fun getSavedApiKey(): String {
        val app = getApplication<Application>()
        val prefs = app.getSharedPreferences("spendai_settings", Context.MODE_PRIVATE)
        return (prefs.getString("llm_api_key", "") ?: "").ifEmpty { prefs.getString("gemini_api_key", "") ?: "" }
    }

    private fun refreshPresent() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val prefs = app.getSharedPreferences("spendai_settings", Context.MODE_PRIVATE)
            val provider = prefs.getString("llm_provider", "GEMINI") ?: "GEMINI"
            val apiKey = (prefs.getString("llm_api_key", "") ?: "").ifEmpty { prefs.getString("gemini_api_key", "") ?: "" }
            val model = prefs.getString("llm_model", "gemma-4-31b-it") ?: "gemma-4-31b-it"
            val baseUrl = prefs.getString("llm_base_url", "") ?: ""

            val hasKey = apiKey.isNotEmpty() || provider == "OLLAMA"
            _ui.update {
                it.copy(
                    provider = provider,
                    apiKey = apiKey,
                    model = model,
                    baseUrl = baseUrl,
                    present = hasKey,
                    done = hasKey
                )
            }
            setup.setModelPresent(hasKey)
        }
    }

    fun isModelContextValid(provider: String, model: String): Boolean {
        val modelLower = model.lowercase()
        when (provider) {
            "GEMINI" -> return true // All Gemini models support >64k
            "CLAUDE" -> return true // All Claude 3/3.5 models support >200k
            "OPENAI" -> {
                if (modelLower.contains("gpt-3.5") || modelLower.contains("gpt-35")) return false
                if (modelLower.contains("gpt-4") && !modelLower.contains("gpt-4o") && !modelLower.contains("128k") && !modelLower.contains("preview")) {
                    // Older GPT-4 models have 8k or 32k context
                    if (modelLower.contains("32k")) return true
                    return false
                }
                return true
            }
            "KIMI" -> {
                if (modelLower.contains("-8k") || modelLower.contains("-32k")) return false
                return true
            }
            "ZHIPU" -> return true // GLM-4 models generally support 128k
            "OLLAMA" -> {
                // Ollama runs user-configured local models; assume true but user should ensure it supports 64k
                return true
            }
        }
        return true
    }

    fun verifyAndSave(
        provider: String,
        apiKey: String,
        model: String,
        baseUrl: String,
        onSuccess: () -> Unit
    ) {
        _ui.update {
            it.copy(
                running = true,
                verificationStatus = VerificationStatus.VERIFYING,
                verificationError = null
            )
        }
        viewModelScope.launch {
            try {
                // Statically check if model context length is valid
                if (!isModelContextValid(provider, model)) {
                    _ui.update {
                        it.copy(
                            running = false,
                            verificationStatus = VerificationStatus.FAILURE,
                            verificationError = "Model '$model' does not support a 64K context window. Please use a model that supports at least 64K context (e.g. gemma-4-31b-it, gpt-4o, claude-3-5-haiku, moonshot-v1-128k)."
                        )
                    }
                    return@launch
                }

                val app = getApplication<Application>()
                val prefs = app.getSharedPreferences("spendai_settings", Context.MODE_PRIVATE)
                
                // Save settings to SharedPreferences
                prefs.edit()
                    .putString("llm_provider", provider)
                    .putString("llm_api_key", apiKey.trim())
                    .putString("llm_model", model.trim())
                    .putString("llm_base_url", baseUrl.trim())
                    .apply()

                // Initialize engine with the new configuration
                val engine = (app as com.spendai.app.SpendAiApp).gemmaInferenceEngine
                engine.shutdown() // Shutdown existing instance to force reload of keys
                engine.initialize(app)

                // Run connection probe (probe sends "Hi" to the system instruction assistant)
                val response = engine.probe("Hi")
                Log.d(TAG, "Verification successful. Probe response: $response")

                _ui.update {
                    it.copy(
                        running = false,
                        verificationStatus = VerificationStatus.SUCCESS,
                        present = true,
                        done = true,
                        provider = provider,
                        apiKey = apiKey,
                        model = model,
                        baseUrl = baseUrl
                    )
                }
                setup.setModelPresent(true)
                onSuccess()
            } catch (t: Throwable) {
                Log.w(TAG, "Verification failed", t)
                _ui.update {
                    it.copy(
                        running = false,
                        verificationStatus = VerificationStatus.FAILURE,
                        verificationError = t.message ?: "Verification failed. Please check your credentials and connection."
                    )
                }
            }
        }
    }

    /**
     * Hook used by the test screen to confirm the model is ready/present.
     */
    fun isModelPresent(): Boolean {
        val key = getSavedApiKey()
        val hasKey = key.isNotEmpty()
        _ui.update { it.copy(apiKey = key, present = hasKey, done = hasKey) }
        viewModelScope.launch { setup.setModelPresent(hasKey) }
        return hasKey
    }

    companion object {
        private const val TAG = "DownloadViewModel"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                DownloadViewModel(app, SetupViewModel(app))
            }
        }
    }
}
