package com.spendai.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestWorkerBuilder
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SmsStatus
import com.spendai.app.worker.DailyParsingWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives [DailyParsingWorker] via the [TestWorkerBuilder] with an
 * in-memory database and the real [SpendAiApp] repositories. Asserts
 * that:
 *   - seeding two UNPARSED rows + running the worker transitions both
 *     to IGNORED (the Phase 1 placeholder behaviour; Phase 2 will
 *     route to PARSED via the LLM)
 *   - a second run finds zero UNPARSED rows and returns success
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DailyParsingWorkerTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private lateinit var app: SpendAiApp

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = context as SpendAiApp
        // Use a per-test in-memory DB to keep state isolated.
        db = androidx.room.Room.inMemoryDatabaseBuilder(
            context, AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @Test
    fun worker_processes_pending_messages() = runBlocking {
        // Seed two UNPARSED rows.
        db.smsDao().insert(RawSmsMessage(
            senderAddress = "Bank_3001", msgBody = "first", timestamp = 1L
        ))
        db.smsDao().insert(RawSmsMessage(
            senderAddress = "Bank_3001", msgBody = "second", timestamp = 2L
        ))

        val worker = TestWorkerBuilder.from(
            context,
            DailyParsingWorker::class.java
        ).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? = null
        }).build()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        assertEquals(0, db.smsDao().countByStatus(SmsStatus.UNPARSED))
        assertEquals(2, db.smsDao().countByStatus(SmsStatus.IGNORED))
    }

    @Test
    fun second_run_finds_no_pending() = runBlocking {
        // No rows seeded.
        val worker = TestWorkerBuilder.from(
            context, DailyParsingWorker::class.java
        ).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(db.smsDao().countByStatus(SmsStatus.UNPARSED) == 0)
    }
}
