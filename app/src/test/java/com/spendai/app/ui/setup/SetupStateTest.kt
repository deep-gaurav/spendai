package com.spendai.app.ui.setup

import com.spendai.app.TestApp


import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
class SetupStateTest {

    @Before
    fun resetStore() = runBlocking {
        SetupRepository(ApplicationProvider.getApplicationContext()).reset()
    }

    @Test
    fun `default state has nothing set`() = runBlocking {
        val repo = SetupRepository(ApplicationProvider.getApplicationContext())
        val initial = repo.state.first()
        assertFalse(initial.permissionsGranted)
        assertFalse(initial.modelPresent)
        assertFalse(initial.modelProbedOk)
        assertFalse(initial.isComplete)
    }

    @Test
    fun `marking each step updates the flow`() = runBlocking {
        val repo = SetupRepository(ApplicationProvider.getApplicationContext())
        repo.setPermissionsGranted(true)
        assertTrue(repo.state.first().permissionsGranted)
        repo.setModelPresent(true)
        assertTrue(repo.state.first().modelPresent)
        repo.setModelProbedOk(true)
        val s = repo.state.first()
        assertTrue(s.isComplete)
    }

    @Test
    fun `reset clears everything`() = runBlocking {
        val repo = SetupRepository(ApplicationProvider.getApplicationContext())
        repo.setPermissionsGranted(true)
        repo.setModelPresent(true)
        repo.setModelProbedOk(true)
        repo.reset()
        val s = repo.state.first()
        assertFalse(s.permissionsGranted)
        assertFalse(s.modelPresent)
        assertFalse(s.modelProbedOk)
    }
}
