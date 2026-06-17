package com.spendai.app.ui.download

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.spendai.app.inference.ModelInstaller
import com.spendai.app.ui.setup.SetupViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

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
)

class DownloadViewModel(
    application: Application,
    private val setup: SetupViewModel,
    private val downloader: ModelDownloader = ModelDownloader(),
) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(DownloadUiState())
    val ui: StateFlow<DownloadUiState> = _ui.asStateFlow()

    private var job: Job? = null

    init {
        refreshPresent()
    }

    fun getSavedApiKey(): String {
        val app = getApplication<Application>()
        val prefs = app.getSharedPreferences("spendai_settings", Context.MODE_PRIVATE)
        return prefs.getString("gemini_api_key", "") ?: ""
    }

    private fun saveApiKey(apiKey: String) {
        val app = getApplication<Application>()
        val prefs = app.getSharedPreferences("spendai_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("gemini_api_key", apiKey.trim()).apply()
    }

    fun onApiKeyChanged(newKey: String) {
        saveApiKey(newKey)
        val hasKey = newKey.trim().isNotEmpty()
        _ui.update { it.copy(apiKey = newKey, present = hasKey, done = hasKey) }
        setup.setModelPresent(hasKey)
    }

    private fun refreshPresent() {
        viewModelScope.launch {
            val key = getSavedApiKey()
            val hasKey = key.isNotEmpty()
            _ui.update { it.copy(apiKey = key, present = hasKey, done = hasKey) }
            setup.setModelPresent(hasKey)
        }
    }

    fun start() {
        // Obsolete local download logic
    }

    fun cancel() {
        // Obsolete local download logic
    }

    fun retry() {
        // Obsolete local download logic
    }

    private fun handleState(state: DownloadState) {
        // Obsolete local download logic
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
