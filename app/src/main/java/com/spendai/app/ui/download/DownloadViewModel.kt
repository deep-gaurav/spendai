package com.spendai.app.ui.download

import android.app.Application
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
 *
 * [present] is true if the model file is already on disk before the user
 * taps Start. In that case we can skip straight to the Continue button.
 */
data class DownloadUiState(
    val present: Boolean = false,
    val running: Boolean = false,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val done: Boolean = false,
    val error: String? = null,
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

    private fun refreshPresent() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val file = File(app.filesDir, "models/${DownloadConfig.HF_FILENAME}")
            val present = file.exists() && file.length() > 0L
            _ui.update { it.copy(present = present, done = present) }
            if (present) {
                setup.setModelPresent(true)
            }
        }
    }

    fun start() {
        if (_ui.value.running || _ui.value.present || _ui.value.done) return
        val app = getApplication<Application>()
        val target = File(app.filesDir, "models/${DownloadConfig.HF_FILENAME}")
        _ui.update { it.copy(running = true, error = null, bytesDownloaded = 0L, totalBytes = -1L) }
        job = viewModelScope.launch {
            downloader.download(
                url = DownloadConfig.HF_RESOLVE_URL,
                destination = target,
                onProgress = { state -> handleState(state) },
            ).onFailure { err ->
                Log.w(TAG, "download() reported failure", err)
                _ui.update { it.copy(running = false, error = err.message ?: "Download failed") }
            }.onSuccess {
                _ui.update { it.copy(running = false, done = true, present = true, error = null) }
                setup.setModelPresent(true)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _ui.update { it.copy(running = false) }
    }

    fun retry() {
        _ui.update { it.copy(error = null) }
        start()
    }

    private fun handleState(state: DownloadState) {
        when (state) {
            is DownloadState.Running -> _ui.update {
                it.copy(
                    running = true,
                    bytesDownloaded = state.bytesDownloaded,
                    totalBytes = state.totalBytes,
                )
            }
            DownloadState.Done -> _ui.update { it.copy(running = false, done = true, present = true) }
            is DownloadState.Failed -> _ui.update {
                it.copy(running = false, error = state.message)
            }
            DownloadState.Idle -> Unit
        }
    }

    /**
     * Hook used by the test screen to confirm the model is on disk
     * before it tries to initialise the engine. Re-runs the same check
     * the screen does, so we never see a stale "present" flag after a
     * user manually deletes the file.
     */
    fun isModelPresent(): Boolean {
        val app = getApplication<Application>()
        val file = File(app.filesDir, "models/${DownloadConfig.HF_FILENAME}")
        val present = file.exists() && file.length() > 0L
        if (present && !_ui.value.present) {
            _ui.update { it.copy(present = true, done = true) }
            viewModelScope.launch { setup.setModelPresent(true) }
        }
        return present
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
