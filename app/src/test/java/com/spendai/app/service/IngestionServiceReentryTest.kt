package com.spendai.app.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.SpendAiApp
import com.spendai.app.domain.ingestion.DateRange
import com.spendai.app.domain.ingestion.IngestionProgress
import com.spendai.app.inference.GemmaInferenceEngine
import com.spendai.app.inference.InferenceState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks down the v6 re-entrancy contract for [IngestionService].
 * A second `onStartCommand` arriving while the first run is
 * still in flight must NOT start a parallel run. The service
 * publishes an `IngestionProgress.Failure` and drops the
 * duplicate intent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = com.spendai.app.TestableSpendAiApp::class, sdk = [33])
class IngestionServiceReentryTest {

    private lateinit var ctx: Context
    private lateinit var app: SpendAiApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext() as SpendAiApp
        ctx = app
        // Grant READ_SMS so the first run doesn't fail the
        // permission check before hanging on the mocked pipeline.
        org.robolectric.shadows.ShadowApplication
            .getInstance().grantPermissions(android.Manifest.permission.READ_SMS)
        pinEngineStateToReady()
    }

    @After
    fun tearDown() { unmockkAll() }

    private fun pinEngineStateToReady() {
        val engineField = SpendAiApp::class.java.getDeclaredField("gemmaInferenceEngine\$delegate")
        engineField.isAccessible = true
        val mockEngine = mockk<GemmaInferenceEngine>(relaxed = true)
        every { mockEngine.state } returns MutableStateFlow(InferenceState.Ready("Test"))
        coEvery { mockEngine.cancelCurrent() } returns Unit
        engineField.set(app, lazy { mockEngine })
    }

    private fun pinPipelineToHang() {
        val pipelineField = SpendAiApp::class.java.getDeclaredField("ingestionPipeline\$delegate")
        pipelineField.isAccessible = true
        val mockPipeline = mockk<com.spendai.app.domain.ingestion.IngestionPipeline>(relaxed = true)
        coEvery {
            mockPipeline.run(any(), any(), any<suspend (IngestionProgress) -> Unit>())
        } coAnswers { awaitCancellation() }
        coEvery {
            mockPipeline.runPending(any<suspend (IngestionProgress) -> Unit>())
        } coAnswers { awaitCancellation() }
        pipelineField.set(app, lazy { mockPipeline })
    }

    @Test
    fun `second intent while busy publishes a Failure event and is dropped`() = runTest {
        pinPipelineToHang()

        val progress0 = IngestionService.progress
        // Force the state to Idle by cancelling anything in flight.
        IngestionService.cancel(ctx)
        runBlocking {
            withTimeout(2_000) { progress0.first { it is IngestionProgress.Idle } }
        }

        val range = DateRange(0L, System.currentTimeMillis())
        val controller1 = Robolectric.buildService(IngestionService::class.java, Intent())
        val svc1 = controller1.create().get()
        svc1.onStartCommand(
            Intent().apply {
                putExtra(IngestionService.EXTRA_START_MILLIS, range.startMillis)
                putExtra(IngestionService.EXTRA_END_MILLIS, range.endMillis)
            },
            0,
            1,
        )

        // Wait until the progress reflects the in-flight run.
        runBlocking {
            withTimeout(2_000) { progress0.first { it is IngestionProgress.LoadingFromSource } }
        }

        // Second intent while the first is still in flight.
        val controller2 = Robolectric.buildService(IngestionService::class.java, Intent())
        val svc2 = controller2.create().get()
        svc2.onStartCommand(
            Intent().apply {
                putExtra(IngestionService.EXTRA_START_MILLIS, range.startMillis)
                putExtra(IngestionService.EXTRA_END_MILLIS, range.endMillis)
            },
            0,
            2,
        )

        // The second intent must surface as a Failure event.
        val failure = runBlocking {
            withTimeout(2_000) {
                progress0.first { it is IngestionProgress.Failure } as IngestionProgress.Failure
            }
        }
        assertNotNull(failure)
        assertTrue(
            "failure message should mention busy: '${failure.message}'",
            failure.message.contains("already running", ignoreCase = true),
        )

        // Cancel the hanging first run.
        svc1.onStartCommand(
            Intent().apply { action = IngestionService.ACTION_CANCEL },
            0,
            3,
        )
    }
}
