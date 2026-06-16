package com.spendai.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SmsStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-backed Room DAO test. Verifies:
 *  - basic insert + read
 *  - status filter returns only matching rows
 *  - unique index on (sender, timestamp) silently dedupes a re-insert
 *  - status transitions propagate
 *
 * The full Robolectric testApplication is heavier than a pure JVM
 * test but it is the only way to exercise Room's SQLite bindings
 * outside an instrumented test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [33])
class SmsDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        // We deliberately use the default android.app.Application here
        // (not SpendAiApp) so that SpendAiApp.onCreate() — which calls
        // WorkManager.getInstance().enqueueUniquePeriodicWork — does not
        // fire. SmsDaoTest is a pure Room test and does not need the
        // service-locator singletons.
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insert_and_read_round_trip() = runTest {
        val id = db.smsDao().insert(
            RawSmsMessage(
                senderAddress = "Bank_3001",
                msgBody = "Rs 250 debited at ZOMATO",
                timestamp = 1_700_000_000_000L
            )
        )
        assertTrue("insert returned row id", id > 0)

        val pending = db.smsDao().getByStatusOnce(SmsStatus.UNPARSED)
        assertEquals(1, pending.size)
        assertEquals("Bank_3001", pending[0].senderAddress)
        assertEquals(SmsStatus.UNPARSED, pending[0].status)
    }

    @Test
    fun unique_index_dedupes_dual_sim_redelivery() = runTest {
        val first = RawSmsMessage(
            senderAddress = "Bank_3001",
            msgBody = "duplicate body",
            timestamp = 1_700_000_000_000L
        )
        val second = first.copy()  // same (sender, timestamp) → must be IGNORED

        assertTrue(db.smsDao().insert(first) > 0)
        assertEquals("second insert should be a no-op", -1L, db.smsDao().insert(second))

        val rows = db.smsDao().getByStatusOnce(SmsStatus.UNPARSED)
        assertEquals(1, rows.size)
    }

    @Test
    fun status_transitions_propagate() = runTest {
        val id = db.smsDao().insert(
            RawSmsMessage(
                senderAddress = "Bank_3001",
                msgBody = "...",
                timestamp = 1L
            )
        )

        db.smsDao().updateStatus(id, SmsStatus.PARSED)
        assertEquals(0, db.smsDao().countByStatus(SmsStatus.UNPARSED))
        assertEquals(1, db.smsDao().countByStatus(SmsStatus.PARSED))

        db.smsDao().updateStatus(id, SmsStatus.IGNORED)
        assertEquals(0, db.smsDao().countByStatus(SmsStatus.PARSED))
        assertEquals(1, db.smsDao().countByStatus(SmsStatus.IGNORED))
    }
}
