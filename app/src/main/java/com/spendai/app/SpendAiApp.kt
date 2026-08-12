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
import com.spendai.app.data.repository.CategoryRepository
import com.spendai.app.data.repository.FinancialSourceRepository
import com.spendai.app.data.repository.MerchantRepository
import com.spendai.app.data.repository.ParsedSmsRepository
import com.spendai.app.data.repository.PendingReviewRepository
import com.spendai.app.data.repository.IngestionLogRepository
import com.spendai.app.data.repository.InsightsRepository
import com.spendai.app.data.repository.ManualCorrectionRepository
import com.spendai.app.data.repository.MonthlySnapshotRepository
import com.spendai.app.data.repository.RepromptJobRepository
import com.spendai.app.data.repository.SmsRepository
import com.spendai.app.data.repository.TransactionLinkRepository
import com.spendai.app.data.repository.TransactionRepository
import com.spendai.app.domain.ingestion.IngestionPipeline
import com.spendai.app.domain.agent.Agent1SmsParser
import com.spendai.app.domain.agent.Agent2EntityResolver
import com.spendai.app.domain.agent.Agent3Auditor
import com.spendai.app.inference.GemmaInferenceEngine
import com.spendai.app.worker.DailyParsingWorker
import java.util.concurrent.TimeUnit

/**
 * Application class and manual service locator.
 *
 * Phase 3 trimmed the agent graph to A1 + A2 (per-message resolve
 * + commit). A3 and its day-batched commit step are gone; every
 * transaction A1 says TRANSACTION lands directly in
 * `spend_transaction` as soon as A2 returns. All long-lived
 * singletons live here; the worker's `doWork()` resolves them off
 * `applicationContext as SpendAiApp`.
 *
 * ## Why not Hilt
 *
 * The class count is still under the threshold where a DI framework
 * pays for itself. The shape of the public surface here is the
 * contract; if the class count grows further, swap the body for
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
open class SpendAiApp : Application() {

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
        MerchantRepository(database.merchantDao(), database.merchantMetadataDao())
    }
    val categoryRepository: CategoryRepository by lazy {
        CategoryRepository(database.categoryDao())
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
    val ingestionLogRepository: IngestionLogRepository by lazy {
        IngestionLogRepository(database.ingestionLogDao())
    }
    val manualCorrectionRepository: ManualCorrectionRepository by lazy {
        ManualCorrectionRepository(database.manualCorrectionDao())
    }
    val repromptJobRepository: RepromptJobRepository by lazy {
        RepromptJobRepository(database.repromptJobDao())
    }

    val insightsRepository: InsightsRepository by lazy {
        InsightsRepository(database.insightsDao())
    }

    val monthlySnapshotRepository: MonthlySnapshotRepository by lazy {
        MonthlySnapshotRepository(database.monthlySnapshotDao())
    }

    val gemmaInferenceEngine: GemmaInferenceEngine by lazy { GemmaInferenceEngine() }

    val agent1SmsParser: Agent1SmsParser by lazy {
        Agent1SmsParser(gemmaInferenceEngine, parsedSmsRepository)
    }
    val agent2EntityResolver: Agent2EntityResolver by lazy {
        Agent2EntityResolver(
            engine = gemmaInferenceEngine,
            database = database,
            sourceRepository = financialSourceRepository,
            accountRepository = accountRepository,
            merchantRepository = merchantRepository,
            categoryRepository = categoryRepository,
            transactionRepository = transactionRepository,
        )
    }
    val agent3Auditor: Agent3Auditor by lazy {
        Agent3Auditor(
            engine = gemmaInferenceEngine,
            database = database,
            transactionRepository = transactionRepository,
            manualCorrectionRepository = manualCorrectionRepository,
        )
    }

    val ingestionPipeline: IngestionPipeline by lazy {
        IngestionPipeline(
            database = database,
            smsRepository = smsRepository,
            parsedSmsRepository = parsedSmsRepository,
            ingestionLogRepository = ingestionLogRepository,
            manualCorrectionRepository = manualCorrectionRepository,
            agent1 = agent1SmsParser,
            agent2 = agent2EntityResolver,
            agent3 = agent3Auditor,
        )
    }

    /**
     * Read-only SQL gateway the agentic insights flow uses to
     * answer "show me my spending" questions. Validates the
     * model-supplied SQL (allowlisted tables, no write side
     * effects, capped LIMIT) before running it against the
     * user's database.
     */
    val sqlExecutor: com.spendai.app.domain.agent.insights.SqlExecutor by lazy {
        com.spendai.app.domain.agent.insights.SqlExecutor(database)
    }

    /**
     * Multi-turn orchestrator for the agentic insights chat.
     * Owns the conversation history and the ReAct loop; the
     * ViewModel layer is a thin pass-through. Lives for the
     * lifetime of the application so a rotation does not drop
     * the in-flight conversation.
     */
    /**
     * Allowlisted write path the Ask-AI flow uses to save
     * user-defined merchant knowledge ("Own Account is me",
     * "MOHAN KUSHWANA is pani puri vendor", etc.). Sibling
     * of [sqlExecutor]; like it, it is the structural safety
     * boundary between the LLM and the user's database.
     */
    val merchantMutator: com.spendai.app.domain.agent.insights.MerchantMutator by lazy {
        com.spendai.app.domain.agent.insights.MerchantMutator(
            database = database,
            merchantRepository = merchantRepository,
            repromptJobRepository = repromptJobRepository,
        )
    }

    /**
     * Multi-turn orchestrator for the agentic insights chat.
     * Owns the conversation history and the ReAct loop; the
     * ViewModel layer is a thin pass-through. Lives for the
     * lifetime of the application so a rotation does not drop
     * the in-flight conversation.
     */
    val agenticInsightsAgent: com.spendai.app.domain.agent.insights.AgenticInsightsAgent by lazy {
        com.spendai.app.domain.agent.insights.AgenticInsightsAgent(
            engine = gemmaInferenceEngine,
            sqlExecutor = sqlExecutor,
            verifier = com.spendai.app.domain.agent.insights.AnswerVerifier(
                engine = gemmaInferenceEngine,
            ),
            merchantMutator = merchantMutator,
        )
    }

    override open fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SpendAI cold start")
        scheduleDailyParsing()
    }

    open fun scheduleDailyParsing() {
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
