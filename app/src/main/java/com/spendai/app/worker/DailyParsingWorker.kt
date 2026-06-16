package com.spendai.app.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spendai.app.SpendAiApp

/**
 * Background worker that consumes `UNPARSED` SMS rows from Room and
 * eventually hands them to the on-device LLM.
 *
 * ## Phase 1 responsibilities
 *
 *  1. Pull every `UNPARSED` message in timestamp order.
 *  2. Iterate them one at a time. The actual inference call is a TODO seam
 *     — the [GemmaInferenceEngine] will be wired here in Phase 2.
 *  3. Mark each message `PARSED` (LLM produced a usable expense record) or
 *     `IGNORED` (LLM said "this is not a financial message").
 *
 * ## Scheduling
 *
 * Two entry points feed the worker:
 *  - A 24h `PeriodicWorkRequest` enqueued from [com.spendai.app.SpendAiApp].
 *  - A one-shot request from [com.spendai.app.receiver.SmsReceiver] so
 *    freshly received messages don't wait a day.
 *
 * The default WorkManager initializer is used; no custom `Configuration`
 * is needed in Phase 1.
 */
class DailyParsingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as SpendAiApp
        val smsRepository = app.smsRepository

        return try {
            val pending = smsRepository.unparsedOnce()
            if (pending.isEmpty()) {
                Log.d(TAG, "No UNPARSED messages; worker exiting early.")
                return Result.success()
            }

            Log.d(TAG, "Processing ${pending.size} UNPARSED messages")

            for (message in pending) {
                val outcome = processOne(message)
                when (outcome) {
                    Outcome.Parsed  -> smsRepository.markParsed(message.id)
                    Outcome.Ignored -> smsRepository.markIgnored(message.id)
                    Outcome.Retry   -> return Result.retry()
                }
            }

            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Worker failed", t)
            // Most LLM failures are transient (GPU reinit, NPU hiccup).
            // Retry with default backoff; WorkManager will exponentially
            // back off per the standard policy.
            Result.retry()
        }
    }

    /**
     * LLM seam. In Phase 2 this becomes:
     *
     *   val prompt = buildPromptFor(message)
     *   val raw    = app.gemmaInferenceEngine.generatePrediction(prompt)
     *   val parsed = parseExpenseJson(raw)
     *   return if (parsed != null) Outcome.Parsed else Outcome.Ignored
     *
     * For now we treat every message as "ignored" so the worker has a
     * working end-to-end loop (UNPARSED → IGNORED) that the test suite
     * can assert against.
     */
    private suspend fun processOne(message: com.spendai.app.data.local.entity.RawSmsMessage): Outcome {
        Log.d(TAG, "TODO inference for id=${message.id} body='${message.msgBody.take(40)}…'")
        return Outcome.Ignored
    }

    private enum class Outcome { Parsed, Ignored, Retry }

    companion object {
        const val UNIQUE_PERIODIC = "spendai.daily-parsing.periodic"
        const val UNIQUE_ONE_SHOT = "spendai.daily-parsing.oneshot"
        private const val TAG = "DailyParsingWorker"
    }
}
