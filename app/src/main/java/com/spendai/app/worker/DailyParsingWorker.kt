package com.spendai.app.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spendai.app.SpendAiApp
import com.spendai.app.domain.ingestion.sources.DatabaseSmsSource
import com.spendai.app.domain.ingestion.DateRange
import com.spendai.app.domain.ingestion.IngestionOutcome
import com.spendai.app.inference.InferenceState

/**
 * Periodic drain of the `UNPARSED` SMS rows. Phase 2.5 reduced this
 * to a thin adapter around [com.spendai.app.domain.ingestion.IngestionPipeline]
 * — every interesting piece of logic (A1, A2, A3, the per-day
 * grouping, the review-queue fallback) lives there, exercised in
 * unit tests.
 *
 * Two entry points feed the worker:
 *  - A 24h `PeriodicWorkRequest` enqueued from
 *    [com.spendai.app.SpendAiApp.scheduleDailyParsing].
 *  - A one-shot request from [com.spendai.app.receiver.SmsReceiver]
 *    so freshly received messages don't wait a day.
 */
class DailyParsingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as SpendAiApp
        return runOnce(app, applicationContext)
    }

    companion object {
        const val UNIQUE_PERIODIC = "spendai.daily-parsing.periodic"
        const val UNIQUE_ONE_SHOT = "spendai.daily-parsing.oneshot"
        private const val TAG = "DailyParsingWorker"

        /**
         * Testable entry point. [doWork] is a thin shim that calls this
         * with the worker\'s [applicationContext]. Pulled out so unit
         * tests can drive the decision logic without having to
         * instantiate a real [androidx.work.WorkerParameters].
         */
        suspend fun runOnce(
            app: SpendAiApp,
            appContext: android.content.Context,
        ): Result {
            if (app.gemmaInferenceEngine.state.value !is InferenceState.Ready) {
                // If the engine is in Error (e.g. a previous run hit a bad
                // compiled-model state and could not reinit), try one
                // reinitialise here before falling back to WorkManager
                // retry. Without this the worker would loop forever on the
                // Error -> retry -> Error path. initialize() is a no-op for
                // Ready/Loading so it's safe to call from any state.
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
    }

}
