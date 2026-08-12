package com.spendai.app.ui.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The user's forced theme choice. [SYSTEM] follows the OS setting. */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun fromStored(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = ThemePreferenceRepository.STORE_NAME,
)

/**
 * Persists [ThemeMode] across process death in its own DataStore,
 * separate from [com.spendai.app.ui.setup.SetupRepository] so
 * "re-run setup" does not also reset the user's theme choice.
 */
class ThemePreferenceRepository(private val context: Context) {

    private val dataStore: DataStore<Preferences> get() = context.themeDataStore

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        ThemeMode.fromStored(prefs[KEY_THEME_MODE])
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    companion object {
        const val STORE_NAME = "spendai_theme"
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }
}
