package com.spendai.app.ui.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Application-scoped ViewModel that exposes the persistent [SetupState]
 * and offers mutation helpers. Held by the [com.spendai.app.SpendAiApp]
 * service locator so every screen observes the same instance.
 */
class SetupViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SetupRepository(application)

    val state: StateFlow<SetupState> = repo.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SetupState(),
    )

    fun setPermissionsGranted(granted: Boolean) {
        viewModelScope.launch { repo.setPermissionsGranted(granted) }
    }

    fun setModelPresent(present: Boolean) {
        viewModelScope.launch { repo.setModelPresent(present) }
    }

    fun setModelProbedOk(ok: Boolean) {
        viewModelScope.launch { repo.setModelProbedOk(ok) }
    }

    fun reset() {
        viewModelScope.launch { repo.reset() }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application)
                SetupViewModel(app)
            }
        }
    }
}
