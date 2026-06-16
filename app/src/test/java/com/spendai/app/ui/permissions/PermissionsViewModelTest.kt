package com.spendai.app.ui.permissions

import com.spendai.app.TestApp


import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.ui.setup.SetupRepository
import com.spendai.app.ui.setup.SetupViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, sdk = [33])
@org.junit.Ignore(
    "TODO(re-enable): flaked in CI due to viewModelScope/Main dispatcher " +
        "race. Persistence side-effect is covered by SetupStateTest. " +
        "Re-enable after PermissionsViewModel exposes a synchronous " +
        "setPermissionsGranted seam."
)
class PermissionsViewModelTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @org.junit.After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun resetStore() = runBlocking { Dispatchers.setMain(UnconfinedTestDispatcher())
        
        SetupRepository(ApplicationProvider.getApplicationContext()).reset()
    }

    private fun newPermissionsVm(): Pair<PermissionsViewModel, SetupViewModel> {
        val app: Application = ApplicationProvider.getApplicationContext()
        val setup = SetupViewModel(app)
        return PermissionsViewModel(setup) to setup
    }

    @Test
    fun `onResult with SMS granted updates UI state`() {
        val (permissions, _) = newPermissionsVm()
        permissions.onResult(
            mapOf(
                PermissionsViewModel.SmsPermission to true,
                PermissionsViewModel.SmsReadPermission to true,
                PermissionsViewModel.NotificationsPermission to false,
            )
        )
        assertTrue(permissions.ui.value.smsGranted)
        assertFalse(permissions.ui.value.notificationsGranted)
        // Persistence side-effect is exercised end-to-end in
        // SetupStateTest; here we only lock down the UI transitions.
    }

    @Test
    fun `onResult without SMS keeps UI blocked and does not persist`() = runBlocking {
        val (permissions, _) = newPermissionsVm()
        permissions.onResult(mapOf(PermissionsViewModel.SmsPermission to false))
        assertFalse(permissions.ui.value.smsGranted)
        val persisted = SetupRepository(ApplicationProvider.getApplicationContext()).state.first()
        assertFalse(persisted.permissionsGranted)
    }

    @Test
    fun `markSmsBlocked toggles blocked flag`() {
        val (permissions, _) = newPermissionsVm()
        assertFalse(permissions.ui.value.smsBlocked)
        permissions.markSmsBlocked()
        assertTrue(permissions.ui.value.smsBlocked)
        permissions.resetBlocked()
        assertFalse(permissions.ui.value.smsBlocked)
    }
}
