package com.spendai.app.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.spendai.app.SpendAiApp
import com.spendai.app.domain.ingestion.sources.DatabaseSmsSource
import com.spendai.app.domain.ingestion.DateRange
import com.spendai.app.domain.ingestion.IngestionOutcome
import com.spendai.app.inference.InferenceState

/**
 * Periodic drain of the `UNPARSED` SMS rows.
 *
 * Two entry points feed the worker:
 *  - A 24h `PeriodicWorkRequest` enqueued from
 *    [com.spendai.app.SpendAiApp.scheduleDailyParsing].
 *  - A one-shot request from [com.spendai.app.receiver.SmsReceiver]
 *    so freshly received messages don't wait a day.
 *  - A one-shot request with [EXTRA_RAW_SMS_ID] from the debug
 *    log's "Retry" button. The worker delegates to
 *    [com.spendai.app.domain.ingestion.IngestionPipeline.runOne]
 *    to re-process a single stuck message.
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
        return runOnce(app, applicationContext)
    }

    companion object {
        const val UNIQUE_PERIODIC = "spendai.daily-parsing.periodic"
        const val UNIQUE_ONE_SHOT = "spendai.daily-parsing.oneshot"
        const val UNIQUE_RETRY = "spendai.daily-parsing.retry"
        const val EXTRA_RAW_SMS_ID = "spendai.extra.RAW_SMS_ID"
        private const val TAG = "DailyParsingWorker"

        suspend fun runOnce(
            app: SpendAiApp,
            appContext: android.content.Context,
        ): Result {
            if (app.gemmaInferenceEngine.state.value !is InferenceState.Ready) {
                try {
                    app.gemmaInferenceEngine.initialize(appContext)
                } catch (t: Throwable) {
                    Log.w(TAG, "Engine reinit failed; retrying later", t)
                    return Result.retry()
                }
            }

            return try {
                val outcome = app.ingestionPipeline.run(
                    source = DatabaseSmsSource(app.smsRepository),
                    range = DateRange.unbounded(),
                    emit = { /* periodic drain logs via the pipeline itself */ },
                )
                when (outcome) {
                    is IngestionOutcome.Success -> Result.success()
                    is IngestionOutcome.Failure -> Result.retry()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Worker failed", t)
                Result.retry()
            }
        }

        /**
         * Per-message retry path. Drives the pipeline on a single
         * raw_sms row and exits. Used by the debug log's "Retry"
         * button for messages stuck in a SKIPPED_A1 / SKIPPED_A2
         * loop.
         */
        suspend fun runOne(app: SpendAiApp, rawSmsId: Long): Result {
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
