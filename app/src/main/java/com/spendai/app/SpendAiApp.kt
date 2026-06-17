package com.spendai.app

import android.app.Application
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.repository.AccountRepository
import com.spendai.app.data.repository.FinancialSourceRepository
import com.spendai.app.data.repository.MerchantRepository
import com.spendai.app.data.repository.ParsedSmsRepository
import com.spendai.app.data.repository.PendingReviewRepository
import com.spendai.app.data.repository.SmsRepository
import com.spendai.app.data.repository.TransactionLinkRepository
import com.spendai.app.data.repository.TransactionRepository
import com.spendai.app.domain.ingestion.IngestionPipeline
import com.spendai.app.domain.agent.Agent1SmsParser
import com.spendai.app.domain.agent.Agent2EntityResolver
import com.spendai.app.domain.agent.Agent3DayCommitter
import com.spendai.app.inference.GemmaInferenceEngine
import com.spendai.app.worker.DailyParsingWorker
import java.util.concurrent.TimeUnit

/**
 * Application class and manual service locator.
 *
 * Phase 2 adds the three on-device agents (A1/A2/A3) and the new
 * repositories. All long-lived singletons live here; the worker's
 * `doWork()` resolves them off `applicationContext as SpendAiApp`.
 *
 * ## Why not Hilt
 *
 * Phase 2 has ~10 long-lived singletons. Still under the threshold
 * where a DI framework pays for itself. The shape of the public
 * surface here is the contract; if the class count grows further,
 * swap the body for `@HiltAndroidApp` + `@Module @InstallIn(SingletonComponent::class)`
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
    val parsedSmsRepository: ParsedSmsRepository by lazy {
        ParsedSmsRepository(database.parsedSmsDao())
    }
    val accountRepository: AccountRepository by lazy {
        AccountRepository(database.accountDao())
    }
    val merchantRepository: MerchantRepository by lazy {
        MerchantRepository(database.merchantDao())
    }
    val transactionRepository: TransactionRepository by lazy {
        TransactionRepository(database.transactionDao())
    }
    val transactionLinkRepository: TransactionLinkRepository by lazy {
        TransactionLinkRepository(database.transactionLinkDao())
    }
    val pendingReviewRepository: PendingReviewRepository by lazy {
        PendingReviewRepository(database.pendingReviewDao())
    }

    val gemmaInferenceEngine: GemmaInferenceEngine by lazy { GemmaInferenceEngine() }

    val agent1SmsParser: Agent1SmsParser by lazy {
        Agent1SmsParser(gemmaInferenceEngine, parsedSmsRepository)
    }
    val agent2EntityResolver: Agent2EntityResolver by lazy {
        Agent2EntityResolver(
            engine = gemmaInferenceEngine,
            sourceRepository = financialSourceRepository,
            accountRepository = accountRepository,
            merchantRepository = merchantRepository,
            transactionRepository = transactionRepository,
        )
    }
    val agent3DayCommitter: Agent3DayCommitter by lazy {
        Agent3DayCommitter(gemmaInferenceEngine)
    }

    val ingestionPipeline: IngestionPipeline by lazy {
        IngestionPipeline(
            database = database,
            smsRepository = smsRepository,
            parsedSmsRepository = parsedSmsRepository,
            agent1 = agent1SmsParser,
            agent2 = agent2EntityResolver,
            agent3 = agent3DayCommitter,
        )
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SpendAI cold start")
        scheduleDailyParsing()
    }

    private fun scheduleDailyParsing() {
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
