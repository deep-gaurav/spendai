package com.spendai.app.ui.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendai.app.ui.setup.SetupViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the permissions screen.
 *
 * @property receiveSmsGranted true when `RECEIVE_SMS` is granted. The
 *   receiver needs this to capture new SMS.
 * @property readSmsGranted true when `READ_SMS` is granted. Needed
 *   for historical ingestion from the OS provider.
 * @property notificationsGranted true when `POST_NOTIFICATIONS` is
 *   granted (API 33+). Optional.
 * @property smsBlocked true when the user has selected "Don't ask
 *   again" on the system dialog. We surface a settings link instead
 *   of re-asking.
 */
data class PermissionsUiState(
    val receiveSmsGranted: Boolean = false,
    val readSmsGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val smsBlocked: Boolean = false,
) {
    /** The onboarding "Continue" only requires the receiver to fire. */
    val canContinue: Boolean get() = receiveSmsGranted
}

class PermissionsViewModel(
    private val setup: SetupViewModel,
) : ViewModel() {

    private val _ui = MutableStateFlow(PermissionsUiState())
    val ui: StateFlow<PermissionsUiState> = _ui.asStateFlow()

    fun onResult(granted: Map<String, Boolean>) {
        val receive = granted[android.Manifest.permission.RECEIVE_SMS] == true
        val read = granted[android.Manifest.permission.READ_SMS] == true
        val notif = granted[android.Manifest.permission.POST_NOTIFICATIONS] == true
        _ui.update {
            it.copy(
                receiveSmsGranted = receive,
                readSmsGranted = read,
                notificationsGranted = notif,
            )
        }
        if (receive && read) {
            viewModelScope.launch { setup.setPermissionsGranted(true) }
        } else if (receive) {
            // Receiver can fire but historical ingest won't work yet.
            // Don't mark setup complete until READ_SMS is also granted
            // so the home surfaces the disabled Ingest CTA explicitly.
            viewModelScope.launch { setup.setPermissionsGranted(false) }
        }
    }

    fun markSmsBlocked() {
        _ui.update { it.copy(smsBlocked = true) }
    }

    fun resetBlocked() {
        _ui.update { it.copy(smsBlocked = false) }
    }
}
