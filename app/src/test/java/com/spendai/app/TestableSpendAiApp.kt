package com.spendai.app

import android.util.Log

/**
 * Test-only [SpendAiApp] subclass that overrides
 * [SpendAiApp.scheduleDailyParsing] to be a no-op. The tests that
 * drive [com.spendai.app.service.IngestionService] or
 * [com.spendai.app.worker.DailyParsingWorker] directly would
 * otherwise fail with
 * `IllegalStateException: WorkManager is not initialized properly`
 * because the production [SpendAiApp.onCreate] calls
 * [SpendAiApp.scheduleDailyParsing] which calls
 * `WorkManager.getInstance(this)`.
 *
 * The lazy fields on the superclass (gemmaInferenceEngine,
 * ingestionPipeline, repositories) are still available; tests that
 * need to swap them via reflection still work.
 */
class TestableSpendAiApp : SpendAiApp() {
    override fun onCreate() {
        super.onCreate()
        Log.i("TestableSpendAiApp", "onCreate: scheduling disabled in tests")
    }

    override fun scheduleDailyParsing() {
        // No-op: tests hand off to IngestionService directly.
    }
}
