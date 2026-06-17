package com.spendai.app.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.spendai.app.SpendAiApp
import com.spendai.app.domain.ingestion.IngestionOutcome
import com.spendai.app.inference.InferenceState
import com.spendai.app.service.IngestionService

/**
 * Thin WorkManager wrapper that hands work off to the foreground
 * [com.spendai.app.service.IngestionService]. The service is the
 * only executor — this worker exists only so the WorkManager
 * scheduler (24h periodic + receiver one-shot) has something to
 * enqueue. The service's re-entrancy guard prevents two runs
 * colliding when the worker fires while the service is already
 * busy.
 *
 * Two entry points:
 *
 *  - A 24h `PeriodicWorkRequest` enqueued from
 *    [com.spendai.app.SpendAiApp.scheduleDailyParsing] — calls
 *    [IngestionService.startPending].
 *  - A one-shot request with [EXTRA_RAW_SMS_ID] from the debug
 *    log's "Retry" button. The worker delegates to
 *    [com.spendai.app.domain.ingestion.IngestionPipeline.runOne]
 *    via the service's pipeline singleton.
 */
class DailyParsingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as SpendAiApp
        val rawSmsId = inputData.getLong(EXTRA_RAW_SMS_ID, -1L)
        if (rawSmsId > 0L) {
            return runOne(app, rawSmsId)
        }
        // Periodic + receiver one-shot: just hand off to the
        // service. The service's busy guard makes a no-op when
        // a run is already in flight, and WorkManager's
        // ExistingWorkPolicy.KEEP semantics on the enqueue side
        // prevent queue pile-up.
        Log.d(TAG, "Worker handing off to IngestionService (pending)")
        IngestionService.startPending(applicationContext)
        return Result.success()
    }

    companion object {
        const val UNIQUE_PERIODIC = "spendai.daily-parsing.periodic"
        const val UNIQUE_ONE_SHOT = "spendai.daily-parsing.oneshot"
        const val UNIQUE_RETRY = "spendai.daily-parsing.retry"
        const val EXTRA_RAW_SMS_ID = "spendai.extra.RAW_SMS_ID"
        private const val TAG = "DailyParsingWorker"

        /**
         * Per-message retry path. Drives the pipeline on a single
         * raw_sms row and exits. Used by the debug log's "Retry"
         * button for messages stuck in a SKIPPED_A1 / SKIPPED_A2
         * loop.
         */
        private suspend fun runOne(app: SpendAiApp, rawSmsId: Long): Result {
            if (app.gemmaInferenceEngine.state.value !is InferenceState.Ready) {
                try {
                    app.gemmaInferenceEngine.initialize(app)
                } catch (t: Throwable) {
                    Log.w(TAG, "Engine reinit failed; retrying later", t)
                    return Result.retry()
                }
            }
            return try {
                val outcome = app.ingestionPipeline.runOne(
                    rawSmsId = rawSmsId,
                    emit = { /* the worker is fire-and-forget */ },
                )
                when (outcome) {
                    is IngestionOutcome.Success -> Result.success()
                    is IngestionOutcome.Failure -> Result.failure()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Per-message retry failed", t)
                Result.retry()
            }
        }

        /**
         * Build input data for the per-message retry path. Used by
         * the debug log's "Retry" button.
         */
        fun retryInputData(rawSmsId: Long): Data =
            Data.Builder().putLong(EXTRA_RAW_SMS_ID, rawSmsId).build()
    }
}
