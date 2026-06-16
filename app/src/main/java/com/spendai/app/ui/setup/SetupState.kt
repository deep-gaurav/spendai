package com.spendai.app.ui.setup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persistent setup state for the Phase 1.5 onboarding flow.
 *
 * Three booleans capture what the user has finished so far:
 *  - [permissionsGranted] : the SMS group is granted.
 *  - [modelPresent]       : the .litertlm model exists in $filesDir/models/.
 *  - [modelProbedOk]      : the test-screen "I'm online" probe passed.
 *
 * The store is a single [DataStore] keyed under [SETUP_STORE_NAME] so it
 * survives process death and uninstall/reinstall (clear) without
 * polluting the rest of the app's preferences.
 */
data class SetupState(
    val permissionsGranted: Boolean = false,
    val modelPresent: Boolean = false,
    val modelProbedOk: Boolean = false,
) {
    val isComplete: Boolean
        get() = permissionsGranted && modelPresent && modelProbedOk
}

private val Context.setupDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SetupRepository.STORE_NAME,
)

class SetupRepository(private val context: Context) {

    private val dataStore: DataStore<Preferences> get() = context.setupDataStore

    val state: Flow<SetupState> = dataStore.data.map { prefs ->
        SetupState(
            permissionsGranted = prefs[KEY_PERMISSIONS] ?: false,
            modelPresent = prefs[KEY_MODEL_PRESENT] ?: false,
            modelProbedOk = prefs[KEY_PROBED_OK] ?: false,
        )
    }

    suspend fun setPermissionsGranted(granted: Boolean) {
        dataStore.edit { it[KEY_PERMISSIONS] = granted }
    }

    suspend fun setModelPresent(present: Boolean) {
        dataStore.edit { it[KEY_MODEL_PRESENT] = present }
    }

    suspend fun setModelProbedOk(ok: Boolean) {
        dataStore.edit { it[KEY_PROBED_OK] = ok }
    }

    suspend fun reset() {
        dataStore.edit { it.clear() }
    }

    companion object {
        const val STORE_NAME = "spendai_setup"
        private val KEY_PERMISSIONS = booleanPreferencesKey("permissions_granted")
        private val KEY_MODEL_PRESENT = booleanPreferencesKey("model_present")
        private val KEY_PROBED_OK = booleanPreferencesKey("model_probed_ok")
    }
}
