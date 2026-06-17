package com.spendai.app.worker

import androidx.test.core.app.ApplicationProvider
import com.spendai.app.SpendAiApp
import com.spendai.app.domain.ingestion.IngestionOutcome
import com.spendai.app.domain.ingestion.IngestionSummary
import com.spendai.app.inference.GemmaInferenceEngine
import com.spendai.app.inference.InferenceState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks down the v6 thin-worker design: the worker is a pure
 * handoff. The periodic + receiver one-shot path just fires
 * [com.spendai.app.service.IngestionService.startPending]. The
 * per-message retry path (with [DailyParsingWorker.EXTRA_RAW_SMS_ID])
 * delegates to [com.spendai.app.domain.ingestion.IngestionPipeline.runOne].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = com.spendai.app.TestableSpendAiApp::class, sdk = [33])
class DailyParsingWorkerErrorStateTest {

    private lateinit var app: SpendAiApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext() as SpendAiApp
    }

    @After
    fun tearDown() { unmockkAll() }

    private fun setEngine(mockEngine: GemmaInferenceEngine) {
        val field = SpendAiApp::class.java.getDeclaredField("gemmaInferenceEngine\$delegate")
        field.isAccessible = true
        field.set(app, lazy { mockEngine })
    }

    private fun setPipeline(mockPipeline: com.spendai.app.domain.ingestion.IngestionPipeline) {
        val field = SpendAiApp::class.java.getDeclaredField("ingestionPipeline\$delegate")
        field.isAccessible = true
        field.set(app, lazy { mockPipeline })
    }

    private fun newWorker(rawSmsId: Long? = null): DailyParsingWorker {
        val params = mockk<androidx.work.WorkerParameters>(relaxed = true)
        val data = if (rawSmsId != null) {
            DailyParsingWorker.retryInputData(rawSmsId)
        } else {
            androidx.work.Data.EMPTY
        }
        every { params.inputData } returns data
        return DailyParsingWorker(app, params)
    }

    @Test
    fun `per-message retry re-initializes engine when in Error then succeeds`() = runTest {
        val engine = mockk<GemmaInferenceEngine>(relaxed = true)
        val pipeline = mockk<com.spendai.app.domain.ingestion.IngestionPipeline>(relaxed = true)
        every { engine.state } returns MutableStateFlow(InferenceState.Error("prior crash"))
        coEvery { engine.initialize(any(), any(), any()) } returns Unit
        coEvery {
            pipeline.runOne(any(), any<suspend (com.spendai.app.domain.ingestion.IngestionProgress) -> Unit>())
        } returns IngestionOutcome.Success(IngestionSummary.EMPTY)
        setEngine(engine)
        setPipeline(pipeline)

        val worker = newWorker(rawSmsId = 42L)
        val result = worker.doWork()
        coVerify(exactly = 1) { engine.initialize(any(), any(), any()) }
        coVerify(exactly = 1) {
            pipeline.runOne(eq(42L), any<suspend (com.spendai.app.domain.ingestion.IngestionProgress) -> Unit>())
        }
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
    }

    @Test
    fun `per-message retry returns Result_retry when engine reinit fails`() = runTest {
        val engine = mockk<GemmaInferenceEngine>(relaxed = true)
        every { engine.state } returns MutableStateFlow(InferenceState.Error("prior crash"))
        coEvery { engine.initialize(any(), any(), any()) } throws
            com.google.ai.edge.litertlm.LiteRtLmJniException("reinit failed")
        setEngine(engine)

        val worker = newWorker(rawSmsId = 42L)
        val result = worker.doWork()
        coVerify(exactly = 1) { engine.initialize(any(), any(), any()) }
        assertEquals(androidx.work.ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `per-message retry returns Result_failure when pipeline returns Failure`() = runTest {
        val engine = mockk<GemmaInferenceEngine>(relaxed = true)
        val pipeline = mockk<com.spendai.app.domain.ingestion.IngestionPipeline>(relaxed = true)
        every { engine.state } returns MutableStateFlow(InferenceState.Ready("GPU"))
        coEvery {
            pipeline.runOne(any(), any<suspend (com.spendai.app.domain.ingestion.IngestionProgress) -> Unit>())
        } returns IngestionOutcome.Failure("still bad")
        setEngine(engine)
        setPipeline(pipeline)

        val worker = newWorker(rawSmsId = 42L)
        val result = worker.doWork()
        coVerify(exactly = 0) { engine.initialize(any(), any(), any()) }
        assertEquals(androidx.work.ListenableWorker.Result.failure(), result)
    }
}
