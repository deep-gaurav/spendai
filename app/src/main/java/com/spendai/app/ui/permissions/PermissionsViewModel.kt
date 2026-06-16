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
 * @property smsGranted true when the SMS group (RECEIVE_SMS + READ_SMS)
 *   is granted. Required to proceed.
 * @property notificationsGranted true when POST_NOTIFICATIONS is granted
 *   (API 33+). Optional; a denial does not block progression.
 * @property smsBlocked true when the user has selected "Don't ask again"
 *   on the system dialog. We surface a settings link instead of
 *   re-asking.
 */
data class PermissionsUiState(
    val smsGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val smsBlocked: Boolean = false,
)

class PermissionsViewModel(
    private val setup: SetupViewModel,
) : ViewModel() {

    private val _ui = MutableStateFlow(PermissionsUiState())
    val ui: StateFlow<PermissionsUiState> = _ui.asStateFlow()

    fun onResult(granted: Map<String, Boolean>) {
        val smsGranted = granted[SmsPermission] == true
        val notificationsGranted = granted[NotificationsPermission] == true
        _ui.update { it.copy(smsGranted = smsGranted, notificationsGranted = notificationsGranted) }
        if (smsGranted) {
            viewModelScope.launch { setup.setPermissionsGranted(true) }
        }
    }

    fun markSmsBlocked() {
        _ui.update { it.copy(smsBlocked = true) }
    }

    fun resetBlocked() {
        _ui.update { it.copy(smsBlocked = false) }
    }

    companion object {
        const val SmsPermission = "android.permission.RECEIVE_SMS"
        const val SmsReadPermission = "android.permission.READ_SMS"
        const val NotificationsPermission = "android.permission.POST_NOTIFICATIONS"
    }
}
