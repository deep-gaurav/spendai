package com.spendai.app.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.RepromptJob
import com.spendai.app.data.local.entity.RepromptJobStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks down the [RepromptJobRepository] contract that the
 * foreground [com.spendai.app.service.IngestionService] depends
 * on for the durable A3 reprompt flow.
 *
 * The behaviours we care about:
 *
 *  - `insert` returns a new id; `getById` round-trips.
 *  - `markAttempt` increments `attemptCount` and stamps
 *    `lastAttemptAt` while leaving `completedAt` null.
 *  - `markTerminal` flips to a terminal status and stamps
 *    `completedAt`; `getStale` ignores terminal rows.
 *  - `getStale` returns PENDING / RUNNING rows whose
 *    `lastAttemptAt` is older than the supplied threshold — the
 *    cold-start scan in the service uses this.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = com.spendai.app.TestApp::class, sdk = [33])
class RepromptJobRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: RepromptJobRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RepromptJobRepository(db.repromptJobDao())
    }

    @After
    fun tearDown() { db.close() }

    private fun newJob(
        status: RepromptJobStatus = RepromptJobStatus.PENDING,
        transactionId: Long? = null,
        lastAttemptAt: Long? = null,
        attemptCount: Int = 0,
    ) = RepromptJob(
        rawSmsIds = "[1, 2, 3]",
        userPrompt = "treat credit as transfer",
        transactionId = transactionId,
        createdAt = 1_000L,
        status = status.name,
        attemptCount = attemptCount,
        lastAttemptAt = lastAttemptAt,
    )

    @Test
    fun `insert returns a new id and getById round-trips`() = runTest {
        val id = repo.insert(newJob())
        assertTrue("expected positive id from insert", id > 0L)
        val row = repo.getById(id)
        assertNotNull(row)
        assertEquals("[1, 2, 3]", row!!.rawSmsIds)
        assertEquals("treat credit as transfer", row.userPrompt)
        assertEquals(RepromptJobStatus.PENDING.name, row.status)
    }

    @Test
    fun `markAttempt increments attempt and leaves completedAt null`() = runTest {
        val id = repo.insert(newJob())
        repo.markAttempt(
            id = id,
            status = RepromptJobStatus.RUNNING,
            attemptCount = 1,
            lastAttemptAt = 5_000L,
        )
        val row = repo.getById(id)!!
        assertEquals(RepromptJobStatus.RUNNING.name, row.status)
        assertEquals(1, row.attemptCount)
        assertEquals(5_000L, row.lastAttemptAt)
        assertNull("attempt should not stamp completedAt", row.completedAt)
    }

    @Test
    fun `markTerminal flips to terminal status and stamps completedAt`() = runTest {
        val id = repo.insert(newJob(status = RepromptJobStatus.RUNNING, attemptCount = 2))
        repo.markTerminal(
            id = id,
            status = RepromptJobStatus.COMPLETED,
            completedAt = 7_000L,
        )
        val row = repo.getById(id)!!
        assertEquals(RepromptJobStatus.COMPLETED.name, row.status)
        assertEquals(7_000L, row.completedAt)
    }

    @Test
    fun `markTerminal FAILED carries the error message`() = runTest {
        val id = repo.insert(newJob())
        repo.markTerminal(
            id = id,
            status = RepromptJobStatus.FAILED,
            completedAt = 8_000L,
            errorMessage = "Engine not READY",
        )
        val row = repo.getById(id)!!
        assertEquals(RepromptJobStatus.FAILED.name, row.status)
        assertEquals("Engine not READY", row.errorMessage)
    }

    @Test
    fun `getStale returns only PENDING or RUNNING rows older than the cutoff`() = runTest {
        val cutoff = 10_000L
        val recentRunning = repo.insert(
            newJob(status = RepromptJobStatus.RUNNING, lastAttemptAt = 10_001L)
        )
        val staleRunning = repo.insert(
            newJob(status = RepromptJobStatus.RUNNING, lastAttemptAt = 5_000L, attemptCount = 1)
        )
        val stalePending = repo.insert(newJob(status = RepromptJobStatus.PENDING))
        val completed = repo.insert(
            newJob(status = RepromptJobStatus.RUNNING, lastAttemptAt = 1_000L)
        )
        repo.markTerminal(id = completed, status = RepromptJobStatus.COMPLETED, completedAt = 6_000L)

        val stale = repo.getStale(cutoff).map { it.id }.toSet()
        assertEquals(setOf(staleRunning, stalePending), stale)
        assertTrue("recent RUNNING should not be stale", recentRunning !in stale)
        assertTrue("COMPLETED should not be stale", completed !in stale)
    }

    @Test
    fun `observeByTransactionId returns an empty flow when no jobs match`() = runTest {
        // Insert a job with transactionId = null (the FK is on
        // a nullable column, so no Transaction row is required).
        val aId = repo.insert(newJob(transactionId = null))
        // No job matches transactionId 12345L; expect an empty list.
        val none = repo.observeByTransactionId(12345L).first()
        assertEquals(0, none.size)
        // The null-txn job is still there for the count query.
        assertEquals(1, repo.count())
        assertEquals(aId, repo.getById(aId)!!.id)
    }

    @Test
    fun `pruneToMostRecent keeps only the newest rows`() = runTest {
        val ids = (1..5).map { repo.insert(newJob()) }
        repo.pruneToMostRecent(keep = 2)
        val remaining = ids.mapNotNull { repo.getById(it) }
        // The two most recently inserted rows survive.
        assertEquals(2, remaining.size)
        assertEquals(ids.last(), remaining.last().id)
        assertEquals(ids[ids.size - 2], remaining.first().id)
    }
}
