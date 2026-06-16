package com.spendai.app

import android.app.Application
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.repository.FinancialSourceRepository
import com.spendai.app.data.repository.SmsRepository
import com.spendai.app.inference.GemmaInferenceEngine
import com.spendai.app.worker.DailyParsingWorker
import java.util.concurrent.TimeUnit

/**
 * Application class and manual service locator.
 *
 * ## Why not Hilt
 *
 * Phase 1 has ~5 long-lived singletons. Wiring them up with a DI
 * framework would be more code (and more build time) than just
 * exposing them as `val`s. The shape of the public surface here is
 * the contract; if the class count grows, swap the body for
 * `@HiltAndroidApp` + `@Module @InstallIn(SingletonComponent::class)`
 * and the call sites stay unchanged.
 *
 * ## Side effects on cold start
 *
 *  - The Room database is opened (synchronous; the file is small).
 *  - WorkManager is initialised and the 24h periodic worker is
 *    enqueued with `KEEP` so multiple cold starts don't reset the
 *    schedule.
 *  - **The LLM is NOT loaded here.** Engine load is expensive and
 *    must be triggered from a UI action or the worker, not from a
 *    cold start that may happen with the user in airplane mode.
 */
class SpendAiApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.get(this) }

    val smsRepository: SmsRepository by lazy { SmsRepository(database.smsDao()) }
    val financialSourceRepository: FinancialSourceRepository by lazy {
        FinancialSourceRepository(database.financialSourceDao())
    }

    val gemmaInferenceEngine: GemmaInferenceEngine by lazy { GemmaInferenceEngine() }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SpendAI cold start")
        scheduleDailyParsing()
    }

    private fun scheduleDailyParsing() {
        // The daily worker needs no network and no charging. It is
        // expected to be cheap: pull UNPARSED rows, run inference,
        // update statuses. If the LLM is uninitialised at this point
        // the worker skips gracefully (TODO seam in Phase 2).
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<DailyParsingWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DailyParsingWorker.UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private companion object {
        const val TAG = "SpendAiApp"
    }
}
