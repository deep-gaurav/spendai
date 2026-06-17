package com.spendai.app.worker

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

/**
 * Locks down the "engine in Error -> reinit -> continue" contract on
 * [DailyParsingWorker]. Without the reinit attempt the worker would
 * loop forever: Error -> retry -> Error -> retry -> ... The fix
 * introduces a single initialize() call before falling back to
 * Result.retry(), matching the README's "the worker will re-init on
 * the next retry" promise that the production code didn't actually
 * implement.
 *
 * The engine and pipeline lazy delegates on [SpendAiApp] are swapped
 * with mocks via reflection for the test. The real production app
 * continues to construct them lazily on first access. We use a
 * plain [SpendAiApp] instance (no Robolectric needed) because
 * [DailyParsingWorker.runOnce] is a pure companion-object function
 * that takes the [SpendAiApp] and [android.content.Context]
 * directly, so the test doesn't need to drive a real WorkManager
 * worker.
 */
class DailyParsingWorkerErrorStateTest {

    private lateinit var app: SpendAiApp

    @Before
    fun setUp() {
        // SpendAiApp is a plain Application subclass; instantiating it
        // directly is fine for unit tests because we never attach it
        // to a Context. The lazy fields are swapped in via reflection
        // before runOnce() accesses them.
        app = SpendAiApp()
    }

    @After
    fun tearDown() { unmockkAll() }

    private fun setEngine(mockEngine: GemmaInferenceEngine) {
        val field = SpendAiApp::class.java.getDeclaredField("gemmaInferenceEngine\$delegate")
        field.isAccessible = true
        val lazyDelegate = lazy { mockEngine }
        field.set(app, lazyDelegate)
    }

    private fun setPipeline(mockPipeline: com.spendai.app.domain.ingestion.IngestionPipeline) {
        val field = SpendAiApp::class.java.getDeclaredField("ingestionPipeline\$delegate")
        field.isAccessible = true
        val lazyDelegate = lazy { mockPipeline }
        field.set(app, lazyDelegate)
    }

    private fun setSmsRepository(repo: com.spendai.app.data.repository.SmsRepository) {
        val field = SpendAiApp::class.java.getDeclaredField("smsRepository\$delegate")
        field.isAccessible = true
        val lazyDelegate = lazy { repo }
        field.set(app, lazyDelegate)
    }

    private fun setDatabase(db: com.spendai.app.data.local.AppDatabase) {
        val field = SpendAiApp::class.java.getDeclaredField("database\$delegate")
        field.isAccessible = true
        val lazyDelegate = lazy { db }
        field.set(app, lazyDelegate)
    }

    @Test
    fun `engine in Error triggers reinit then continues`() = runTest {
        val engine = mockk<GemmaInferenceEngine>(relaxed = true)
        val pipeline = mockk<com.spendai.app.domain.ingestion.IngestionPipeline>(relaxed = true)
        every { engine.state } returns MutableStateFlow(InferenceState.Error("prior crash"))
        coEvery { engine.initialize(any(), any(), any()) } returns Unit
        coEvery {
            pipeline.run(any(), any(), any<suspend (com.spendai.app.domain.ingestion.IngestionProgress) -> Unit>())
        } returns IngestionOutcome.Success(IngestionSummary.EMPTY)
        setEngine(engine)
        setPipeline(pipeline)
        setSmsRepository(mockk(relaxed = true))
        setDatabase(mockk(relaxed = true))

        val result = DailyParsingWorker.runOnce(app, app)
        coVerify(exactly = 1) { engine.initialize(any(), any(), any()) }
        coVerify(exactly = 1) {
            pipeline.run(any(), any(), any<suspend (com.spendai.app.domain.ingestion.IngestionProgress) -> Unit>())
        }
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
    }

    @Test
    fun `engine reinit failure returns Result_retry`() = runTest {
        val engine = mockk<GemmaInferenceEngine>(relaxed = true)
        every { engine.state } returns MutableStateFlow(InferenceState.Error("prior crash"))
        coEvery { engine.initialize(any(), any(), any()) } throws
            com.google.ai.edge.litertlm.LiteRtLmJniException("reinit failed")
        setEngine(engine)

        val result = DailyParsingWorker.runOnce(app, app)
        coVerify(exactly = 1) { engine.initialize(any(), any(), any()) }
        assertEquals(androidx.work.ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `engine already Ready skips reinit and runs pipeline`() = runTest {
        val engine = mockk<GemmaInferenceEngine>(relaxed = true)
        val pipeline = mockk<com.spendai.app.domain.ingestion.IngestionPipeline>(relaxed = true)
        every { engine.state } returns MutableStateFlow(InferenceState.Ready("GPU"))
        coEvery {
            pipeline.run(any(), any(), any<suspend (com.spendai.app.domain.ingestion.IngestionProgress) -> Unit>())
        } returns IngestionOutcome.Success(IngestionSummary.EMPTY)
        setEngine(engine)
        setPipeline(pipeline)
        setSmsRepository(mockk(relaxed = true))
        setDatabase(mockk(relaxed = true))

        val result = DailyParsingWorker.runOnce(app, app)
        coVerify(exactly = 0) { engine.initialize(any(), any(), any()) }
        coVerify(exactly = 1) {
            pipeline.run(any(), any(), any<suspend (com.spendai.app.domain.ingestion.IngestionProgress) -> Unit>())
        }
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
    }
}
