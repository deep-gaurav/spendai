package com.spendai.app.ui.review

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.TestApp
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.PendingReview
import com.spendai.app.data.local.entity.PendingReviewKind
import com.spendai.app.data.local.entity.PendingReviewResolution
import com.spendai.app.data.repository.PendingReviewRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end exercise of the accept / reject flow against a real
 * v2 [AppDatabase]. [ReviewViewModel] itself is a thin wrapper over
 * [PendingReviewRepository] — the contracts the VM relies on are
 * `getOpenOnce` / `resolve`, so testing those is the meaningful
 * coverage. (The VM constructor pulls from [com.spendai.app.SpendAiApp]
 * which is a service-locator that requires the full app context.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, sdk = [33])
class ReviewViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var reviews: PendingReviewRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        reviews = PendingReviewRepository(db.pendingReviewDao())
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `empty db produces empty state`() = runTest {
        assertTrue(reviews.getOpenOnce(PendingReviewKind.SOURCE.name).isEmpty())
    }

    @Test
    fun `accept resolves a SOURCE row with ACCEPTED`() = runTest {
        val id = reviews.insert(
            PendingReview(
                kind = PendingReviewKind.SOURCE.name,
                targetId = 42L,
                promptSummary = "label me",
                suggestedJson = "{}",
                createdAt = 1L,
            )
        )
        reviews.resolve(id, PendingReviewResolution.ACCEPTED, 100L)
        val row = reviews.getById(id)
        assertNotNull(row)
        assertEquals(PendingReviewResolution.ACCEPTED.name, row!!.resolution)
        assertEquals(100L, row.resolvedAt)
    }

    @Test
    fun `reject resolves with REJECTED`() = runTest {
        val id = reviews.insert(
            PendingReview(
                kind = PendingReviewKind.TRANSACTION.name,
                targetId = 7L,
                promptSummary = "verify me",
                suggestedJson = "{}",
                createdAt = 1L,
            )
        )
        reviews.resolve(id, PendingReviewResolution.REJECTED, 200L)
        val row = reviews.getById(id)
        assertEquals(PendingReviewResolution.REJECTED.name, row!!.resolution)
        assertEquals(200L, row.resolvedAt)
    }

    @Test
    fun `open queue excludes resolved rows`() = runTest {
        val open1 = reviews.insert(
            PendingReview(
                kind = PendingReviewKind.SOURCE.name,
                targetId = 1L,
                promptSummary = "open",
                suggestedJson = "{}",
                createdAt = 1L,
            )
        )
        val closed = reviews.insert(
            PendingReview(
                kind = PendingReviewKind.SOURCE.name,
                targetId = 2L,
                promptSummary = "closed",
                suggestedJson = "{}",
                createdAt = 2L,
            )
        )
        reviews.resolve(closed, PendingReviewResolution.ACCEPTED, 100L)
        val open = reviews.getOpenOnce(PendingReviewKind.SOURCE.name)
        assertEquals(1, open.size)
        assertEquals(open1, open[0].id)
    }
}
