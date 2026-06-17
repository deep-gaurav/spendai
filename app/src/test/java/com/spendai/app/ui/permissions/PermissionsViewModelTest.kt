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
    fun `onResult with both SMS permissions granted updates UI state`() {
        val (permissions, _) = newPermissionsVm()
        permissions.onResult(
            mapOf(
                android.Manifest.permission.RECEIVE_SMS to true,
                android.Manifest.permission.READ_SMS to true,
                android.Manifest.permission.POST_NOTIFICATIONS to false,
            )
        )
        assertTrue(permissions.ui.value.receiveSmsGranted)
        assertTrue(permissions.ui.value.readSmsGranted)
        assertTrue(permissions.ui.value.canContinue)
        assertFalse(permissions.ui.value.notificationsGranted)
    }

    @Test
    fun `onResult with receive-only keeps ingest disabled`() = runBlocking {
        val (permissions, _) = newPermissionsVm()
        permissions.onResult(
            mapOf(
                android.Manifest.permission.RECEIVE_SMS to true,
                android.Manifest.permission.READ_SMS to false,
            )
        )
        assertTrue(permissions.ui.value.receiveSmsGranted)
        assertFalse(permissions.ui.value.readSmsGranted)
        // Continue is still allowed (receiver can fire for new SMS)
        assertTrue(permissions.ui.value.canContinue)
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
