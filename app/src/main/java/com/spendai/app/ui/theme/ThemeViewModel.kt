package com.spendai.app.ui.theme

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Application-scoped ViewModel for the forced theme choice.
 * Instantiated once at the top of [com.spendai.app.ui.MainActivity]'s
 * composable tree — above [SpendAiTheme] — so every screen reads
 * and mutates the same instance, mirroring
 * [com.spendai.app.ui.setup.SetupViewModel].
 */
class ThemeViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = ThemePreferenceRepository(application)

    val themeMode: StateFlow<ThemeMode> = repo.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ThemeMode.SYSTEM,
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repo.setThemeMode(mode) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application)
                ThemeViewModel(app)
            }
        }
    }
}
