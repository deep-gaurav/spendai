package com.spendai.app.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.ManualCorrection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-JVM (Robolectric) tests for the manual-correction repo. The
 * repo is a thin facade over [com.spendai.app.data.local.dao.ManualCorrectionDao];
 * these tests focus on the ordering and the prune contract that
 * the A3 prompt loader relies on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [33])
class ManualCorrectionRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ManualCorrectionRepository
    private var rawId: Long = 0L

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ManualCorrectionRepository(db.manualCorrectionDao())
        runBlocking {
            rawId = db.smsDao().insert(
                com.spendai.app.data.local.entity.RawSmsMessage(
                    senderAddress = "VK-TEST",
                    msgBody = "test",
                    timestamp = 1L,
                )
            )
        }
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `getRecent returns newest first up to the limit`() = runBlocking {
        for (i in 1..5) {
            repo.insert(
                ManualCorrection(
                    rawSmsId = rawId,
                    userPrompt = "prompt $i",
                    createdAt = i.toLong(),
                )
            )
        }
        val recent = repo.getRecent(limit = 3)
        assertEquals(3, recent.size)
        assertEquals("prompt 5", recent[0].userPrompt)
        assertEquals("prompt 4", recent[1].userPrompt)
        assertEquals("prompt 3", recent[2].userPrompt)
    }

    @Test
    fun `pruneToMostRecent keeps the newest keep rows`() = runBlocking {
        for (i in 1..10) {
            repo.insert(
                ManualCorrection(
                    rawSmsId = rawId,
                    userPrompt = "prompt $i",
                    createdAt = i.toLong(),
                )
            )
        }
        assertEquals(10, repo.count())
        repo.pruneToMostRecent(keep = 3)
        assertEquals(3, repo.count())
        val remaining = repo.getRecent(limit = 5)
        assertEquals(listOf("prompt 10", "prompt 9", "prompt 8"), remaining.map { it.userPrompt })
    }

    @Test
    fun `getById returns the inserted row`() = runBlocking {
        val id = repo.insert(
            ManualCorrection(
                rawSmsId = rawId,
                userPrompt = "hello",
                createdAt = 1L,
            )
        )
        val row = repo.getById(id)
        assertEquals("hello", row?.userPrompt)
        assertEquals(rawId, row?.rawSmsId)
    }
}
