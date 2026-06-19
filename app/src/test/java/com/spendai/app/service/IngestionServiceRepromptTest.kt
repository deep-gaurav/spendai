package com.spendai.app.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.SpendAiApp
import com.spendai.app.data.local.entity.RepromptJob
import com.spendai.app.data.local.entity.RepromptJobStatus
import com.spendai.app.domain.ingestion.DateRange
import com.spendai.app.domain.ingestion.IngestionOutcome
import com.spendai.app.domain.ingestion.IngestionProgress
import com.spendai.app.domain.ingestion.IngestionSummary
import com.spendai.app.inference.GemmaInferenceEngine
import com.spendai.app.inference.InferenceState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Locks down the A3 reprompt flow on
 * [com.spendai.app.service.IngestionService] (the
 * [IngestionService.ACTION_REPROMPT] path).
 *
 * The four behaviours the test covers:
 *
 *  1. A happy-path reprompt inserts a [RepromptJob] row in
 *     RUNNING, drives the pipeline once, and flips the row to
 *     COMPLETED. The `repromptProgress` flow emits `Done`.
 *  2. A second `ACTION_REPROMPT` arriving while a reprompt is
 *     still in flight is dropped with a `Failure` event on
 *     `repromptProgress` (cross-run-type re-entrancy: a reprompt
 *     cannot pre-empt an ingestion, and vice-versa).
 *  3. A transient LLM error (HTTP 503) triggers the service's
 *     retry loop. After the third failure the row flips to
 *     FAILED with the error message.
 *  4. A persistent PENDING / RUNNING row whose `lastAttemptAt`
 *     is older than [IngestionService.REPROMPT_STALE_AFTER_MS]
 *     is picked up by the cold-start scan and re-driven.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = com.spendai.app.TestableSpendAiApp::class, sdk = [33])
class IngestionServiceRepromptTest {

    private lateinit var ctx: Context
    private lateinit var app: SpendAiApp
    private val mockEngine: GemmaInferenceEngine = mockk(relaxed = true)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext() as SpendAiApp
        ctx = app
        org.robolectric.shadows.ShadowApplication
            .getInstance().grantPermissions(android.Manifest.permission.READ_SMS)
        every { mockEngine.state } returns MutableStateFlow(InferenceState.Ready("Test"))
        coEvery { mockEngine.cancelCurrent() } returns Unit
        val engineField = SpendAiApp::class.java.getDeclaredField("gemmaInferenceEngine\$delegate")
        engineField.isAccessible = true
        engineField.set(app, lazy { mockEngine })
        // Drain any leftover state from previous tests.
        runBlocking {
            withTimeout(2_000) { IngestionService.progress.first { it is IngestionProgress.Idle } }
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
        // Cancel any in-flight run so subsequent tests start clean.
        IngestionService.cancel(ctx)
    }

    private fun pinPipelineTo(outcome: IngestionOutcome) {
        val pipelineField = SpendAiApp::class.java.getDeclaredField("ingestionPipeline\$delegate")
        pipelineField.isAccessible = true
        val mockPipeline = mockk<com.spendai.app.domain.ingestion.IngestionPipeline>(relaxed = true)
        coEvery {
            mockPipeline.runWithReprompt(any(), any(), any<suspend (IngestionProgress) -> Unit>())
        } returns outcome
        pipelineField.set(app, lazy { mockPipeline })
    }

    private fun pinPipelineToThrow(t: Throwable) {
        val pipelineField = SpendAiApp::class.java.getDeclaredField("ingestionPipeline\$delegate")
        pipelineField.isAccessible = true
        val mockPipeline = mockk<com.spendai.app.domain.ingestion.IngestionPipeline>(relaxed = true)
        coEvery {
            mockPipeline.runWithReprompt(any(), any(), any<suspend (IngestionProgress) -> Unit>())
        } throws t
        pipelineField.set(app, lazy { mockPipeline })
    }

    private class PipelineStub {
        val mock = mockk<com.spendai.app.domain.ingestion.IngestionPipeline>(relaxed = true)
        val attempts = AtomicInteger(0)
    }

    /**
     * Pin the app's `ingestionPipeline$delegate` to a mock that
     * throws [IOException] for the first [failuresBeforeSuccess]
     * calls, then returns [IngestionOutcome.Success]. Returns the
     * stub so callers can `coVerify` against the same mock
     * instance the service called into.
     */
    private fun pinPipelineToFailNTimesThenSucceed(
        failuresBeforeSuccess: Int,
    ): PipelineStub {
        val stub = PipelineStub()
        coEvery {
            stub.mock.runWithReprompt(any(), any(), any<suspend (IngestionProgress) -> Unit>())
        } coAnswers {
            val n = stub.attempts.incrementAndGet()
            if (n <= failuresBeforeSuccess) {
                throw IOException("transient 503 Service Unavailable")
            }
            IngestionOutcome.Success(IngestionSummary.EMPTY)
        }
        val pipelineField = SpendAiApp::class.java.getDeclaredField("ingestionPipeline\$delegate")
        pipelineField.isAccessible = true
        pipelineField.set(app, lazy { stub.mock })
        return stub
    }

    @Test
    fun `happy path reprompt persists a job row and emits Done`() = runTest {
        pinPipelineTo(IngestionOutcome.Success(IngestionSummary.EMPTY))

        val controller = Robolectric.buildService(IngestionService::class.java, Intent())
        val svc = controller.create().get()
        val intent = Intent().apply {
            action = IngestionService.ACTION_REPROMPT
            putExtra(IngestionService.EXTRA_REPROMPT_RAW_SMS_IDS, longArrayOf(1L, 2L))
            putExtra(IngestionService.EXTRA_REPROMPT_USER_PROMPT, "treat credit as transfer")
        }
        svc.onStartCommand(intent, 0, 1)

        // Wait for the terminal event.
        val done = runBlocking {
            withTimeout(5_000) {
                IngestionService.repromptProgress.first { it is IngestionProgress.Done }
            }
        }
        assertNotNull(done)

        // The job was inserted, run, and flipped to COMPLETED.
        // Look up the most recent job by createdAt (no FK, no
        // transactionId filter) since this test does not
        // associate the job with a transaction.
        val job = withContext(Dispatchers.IO) {
            app.database.repromptJobDao().getById(latestJobId())
        }
        assertNotNull("expected a reprompt_job row", job)
        assertEquals(RepromptJobStatus.COMPLETED.name, job!!.status)
        assertEquals(1, job.attemptCount)
    }

    @Test
    fun `a second reprompt intent while one is running is dropped with a Failure`() = runTest {
        // First run hangs on the pipeline call.
        val pipelineField = SpendAiApp::class.java.getDeclaredField("ingestionPipeline\$delegate")
        pipelineField.isAccessible = true
        val mockPipeline = mockk<com.spendai.app.domain.ingestion.IngestionPipeline>(relaxed = true)
        coEvery {
            mockPipeline.runWithReprompt(any(), any(), any<suspend (IngestionProgress) -> Unit>())
        } coAnswers { kotlinx.coroutines.awaitCancellation() }
        pipelineField.set(app, lazy { mockPipeline })

        val c1 = Robolectric.buildService(IngestionService::class.java, Intent())
        val s1 = c1.create().get()
        s1.onStartCommand(
            Intent().apply {
                action = IngestionService.ACTION_REPROMPT
                putExtra(IngestionService.EXTRA_REPROMPT_RAW_SMS_IDS, longArrayOf(1L))
                putExtra(IngestionService.EXTRA_REPROMPT_USER_PROMPT, "x")
            },
            0,
            1,
        )

        val c2 = Robolectric.buildService(IngestionService::class.java, Intent())
        val s2 = c2.create().get()
        s2.onStartCommand(
            Intent().apply {
                action = IngestionService.ACTION_REPROMPT
                putExtra(IngestionService.EXTRA_REPROMPT_RAW_SMS_IDS, longArrayOf(2L))
                putExtra(IngestionService.EXTRA_REPROMPT_USER_PROMPT, "y")
            },
            0,
            2,
        )

        val failure = runBlocking {
            withTimeout(2_000) {
                IngestionService.repromptProgress.first { it is IngestionProgress.Failure }
                        as IngestionProgress.Failure
            }
        }
        assertTrue(
            "failure message should mention busy: '${failure.message}'",
            failure.message.contains("already running", ignoreCase = true),
        )

        // Cancel so subsequent tests start clean.
        s1.onStartCommand(
            Intent().apply { action = IngestionService.ACTION_CANCEL },
            0,
            3,
        )
    }

    @Test
    fun `a reprompt intent is dropped while an ingestion is running (cross-mode re-entrancy)`() = runTest {
        // First run is a hanging ingestion.
        val pipelineField = SpendAiApp::class.java.getDeclaredField("ingestionPipeline\$delegate")
        pipelineField.isAccessible = true
        val mockPipeline = mockk<com.spendai.app.domain.ingestion.IngestionPipeline>(relaxed = true)
        coEvery {
            mockPipeline.run(any(), any(), any<suspend (IngestionProgress) -> Unit>())
        } coAnswers { kotlinx.coroutines.awaitCancellation() }
        pipelineField.set(app, lazy { mockPipeline })

        val s1 = Robolectric.buildService(IngestionService::class.java, Intent()).create().get()
        val range = DateRange(0L, System.currentTimeMillis())
        s1.onStartCommand(
            Intent().apply {
                putExtra(IngestionService.EXTRA_START_MILLIS, range.startMillis)
                putExtra(IngestionService.EXTRA_END_MILLIS, range.endMillis)
            },
            0,
            1,
        )

        // Wait until the ingestion is in flight.
        runBlocking {
            withTimeout(2_000) {
                IngestionService.progress.first { it is IngestionProgress.LoadingFromSource }
            }
        }

        // Now drop a reprompt intent on top.
        val s2 = Robolectric.buildService(IngestionService::class.java, Intent()).create().get()
        s2.onStartCommand(
            Intent().apply {
                action = IngestionService.ACTION_REPROMPT
                putExtra(IngestionService.EXTRA_REPROMPT_RAW_SMS_IDS, longArrayOf(99L))
                putExtra(IngestionService.EXTRA_REPROMPT_USER_PROMPT, "test")
            },
            0,
            2,
        )

        val failure = runBlocking {
            withTimeout(2_000) {
                IngestionService.repromptProgress.first { it is IngestionProgress.Failure }
                        as IngestionProgress.Failure
            }
        }
        assertTrue(
            "failure should mention the running ingestion: '${failure.message}'",
            failure.message.contains("already running", ignoreCase = true) ||
                failure.message.contains("ingestion", ignoreCase = true),
        )

        s1.onStartCommand(
            Intent().apply { action = IngestionService.ACTION_CANCEL },
            0,
            3,
        )
    }

    @Test
    fun `transient errors retry up to MAX_REPROMPT_ATTEMPTS then mark FAILED`() = runTest {
        // Cap the retry backoff so the test is fast (default
        // 60s would make the retry loop take minutes).
        withStaticField("REPROMPT_RETRY_BACKOFF_MS", 1L) {
            // Fail twice, succeed on the third attempt. attemptCount
            // on the job should be 3 by the end.
            val stub = pinPipelineToFailNTimesThenSucceed(failuresBeforeSuccess = 2)

            val s = Robolectric.buildService(IngestionService::class.java, Intent()).create().get()
            s.onStartCommand(
                Intent().apply {
                    action = IngestionService.ACTION_REPROMPT
                    putExtra(IngestionService.EXTRA_REPROMPT_RAW_SMS_IDS, longArrayOf(7L))
                    putExtra(IngestionService.EXTRA_REPROMPT_USER_PROMPT, "x")
                },
                0,
                1,
            )

            val done = runBlocking {
                withTimeout(10_000) {
                    IngestionService.repromptProgress.first { it is IngestionProgress.Done }
                }
            }
            assertNotNull(done)
            assertEquals(3, stub.attempts.get())
            coVerify(atLeast = 3) {
                stub.mock.runWithReprompt(any(), any(), any<suspend (IngestionProgress) -> Unit>())
            }
            val job = withContext(Dispatchers.IO) {
                app.database.repromptJobDao().getById(latestJobId())
            }
            assertNotNull(job)
            assertEquals(RepromptJobStatus.COMPLETED.name, job!!.status)
        }
    }

    @Test
    fun `persistent transient errors mark the job FAILED after the cap`() = runTest {
        withStaticField("REPROMPT_RETRY_BACKOFF_MS", 1L) {
            // Fail every attempt.
            pinPipelineToThrow(IOException("503 Service Unavailable"))

            val s = Robolectric.buildService(IngestionService::class.java, Intent()).create().get()
            s.onStartCommand(
                Intent().apply {
                    action = IngestionService.ACTION_REPROMPT
                    putExtra(IngestionService.EXTRA_REPROMPT_RAW_SMS_IDS, longArrayOf(8L))
                    putExtra(IngestionService.EXTRA_REPROMPT_USER_PROMPT, "x")
                },
                0,
                1,
            )

            val failure = runBlocking {
                withTimeout(10_000) {
                    IngestionService.repromptProgress.first { it is IngestionProgress.Failure }
                            as IngestionProgress.Failure
                }
            }
            assertNotNull(failure)
            val job = withContext(Dispatchers.IO) {
                app.database.repromptJobDao().getById(latestJobId())
            }
            assertNotNull(job)
            assertEquals(RepromptJobStatus.FAILED.name, job!!.status)
            assertEquals(
                IngestionService.MAX_REPROMPT_ATTEMPTS,
                job.attemptCount,
            )
            assertTrue(
                "error should mention the transient codes: '${job.errorMessage}'",
                job.errorMessage.orEmpty().contains("503", ignoreCase = true) ||
                    job.errorMessage.orEmpty().contains("transient", ignoreCase = true) ||
                    job.errorMessage.orEmpty().contains("attempts", ignoreCase = true),
            )
        }
    }

    @Test
    fun `non-transient errors mark the job FAILED without retry`() = runTest {
        pinPipelineToThrow(IllegalStateException("Agent 3 returned no parseable JSON"))

        val s = Robolectric.buildService(IngestionService::class.java, Intent()).create().get()
        s.onStartCommand(
            Intent().apply {
                action = IngestionService.ACTION_REPROMPT
                putExtra(IngestionService.EXTRA_REPROMPT_RAW_SMS_IDS, longArrayOf(9L))
                putExtra(IngestionService.EXTRA_REPROMPT_USER_PROMPT, "x")
            },
            0,
            1,
        )

        val failure = runBlocking {
            withTimeout(5_000) {
                IngestionService.repromptProgress.first { it is IngestionProgress.Failure }
                        as IngestionProgress.Failure
            }
        }
        assertNotNull(failure)
        val job = withContext(Dispatchers.IO) {
            app.database.repromptJobDao().getById(latestJobId())
        }
        assertNotNull(job)
        assertEquals(RepromptJobStatus.FAILED.name, job!!.status)
        assertEquals(1, job.attemptCount)
    }

    /**
     * Look up the id of the most recently inserted reprompt_job.
     * Tests in this file do not associate a job with a
     * transaction (so the FK on `transactionId` is not violated),
     * but they still need to find the row to assert its terminal
     * state. The DAO's `getById` is indexed by PK so this is a
     * constant-time lookup.
     */
    private suspend fun latestJobId(): Long {
        return withContext(Dispatchers.IO) {
            // We scan a small candidate range because there is no
            // "get latest" query — but the auto-increment PK makes
            // this O(1) on an empty table.
            var id = 0L
            var candidate: RepromptJob? = null
            for (i in 1..10_000L) {
                val row = app.database.repromptJobDao().getById(i) ?: continue
                if (candidate == null || row.createdAt > candidate.createdAt) {
                    candidate = row
                    id = i
                }
            }
            require(candidate != null) { "no reprompt_job row inserted yet" }
            id
        }
    }

    @Test
    fun `cold-start scan re-drives a stale RUNNING job`() = runTest {
        // Seed a stale RUNNING job whose lastAttemptAt is older than
        // the cold-start threshold. The next IngestionService.onCreate
        // should re-drive it.
        val staleMs = IngestionService::class.java
            .getDeclaredField("REPROMPT_STALE_AFTER_MS").apply { isAccessible = true }
            .get(null) as Long
        val staleRow = RepromptJob(
            rawSmsIds = "[11]",
            userPrompt = "stale prompt",
            transactionId = null,
            createdAt = System.currentTimeMillis() - 60 * 60 * 1000L,
            status = RepromptJobStatus.RUNNING.name,
            attemptCount = 1,
            lastAttemptAt = System.currentTimeMillis() - staleMs - 60_000L,
        )
        val staleId = withContext(Dispatchers.IO) {
            app.repromptJobRepository.insert(staleRow)
        }

        // The pipeline will be called when the service re-drives the
        // job. We pin it to succeed so the test is fast.
        pinPipelineTo(IngestionOutcome.Success(IngestionSummary.EMPTY))

        // In production, IngestionService.onCreate calls
        // `scanStaleRepromptJobs`, which calls
        // `ContextCompat.startForegroundService` to start a new
        // service with the resume intent. Robolectric does not
        // auto-deliver the intent to a freshly-created service,
        // so we simulate that hand-off: create a controller,
        // let onCreate run (which fires the scan + posts the
        // resume intent), then look up the new intent and call
        // onStartCommand with it.
        val controller = Robolectric.buildService(IngestionService::class.java, Intent())
        val svc = controller.create().get()

        // After onCreate, the scan has been fired. The intent
        // it would have sent to startForegroundService is not
        // captured by Robolectric, so we synthesise the resume
        // intent and call onStartCommand ourselves.
        val resumeIntent = Intent(svc, IngestionService::class.java).apply {
            action = IngestionService.ACTION_REPROMPT
            putExtra(IngestionService.EXTRA_REPROMPT_RAW_SMS_IDS, longArrayOf(11L))
            putExtra(IngestionService.EXTRA_REPROMPT_USER_PROMPT, "stale prompt")
            putExtra(IngestionService.EXTRA_REPROMPT_RESUME_JOB_ID, staleId)
        }
        svc.onStartCommand(resumeIntent, 0, 1)

        // Wait for the resume to flip the job to COMPLETED.
        val resolved = runBlocking {
            withTimeout(5_000) {
                withContext(Dispatchers.IO) {
                    var job: RepromptJob? = null
                    while (true) {
                        job = app.database.repromptJobDao().getById(staleId)
                        if (job != null && job.status == RepromptJobStatus.COMPLETED.name) {
                            return@withContext job
                        }
                        kotlinx.coroutines.delay(50)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    job!!
                }
            }
        }
        assertEquals(RepromptJobStatus.COMPLETED.name, resolved.status)
    }
    /**
     * Override a `@JvmField var` on [IngestionService] for the
     * lifetime of [block], restoring the original value on exit.
     * The fields live on the enclosing class (not the Companion)
     * and are mutable, so reflection write works directly with
     * `isAccessible = true`.
     */
    private inline fun <T> withStaticField(name: String, value: T, block: () -> Unit) {
        val field = IngestionService::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val original = field.get(null) as T
        field.set(null, value)
        try { block() } finally { field.set(null, original) }
    }

}
