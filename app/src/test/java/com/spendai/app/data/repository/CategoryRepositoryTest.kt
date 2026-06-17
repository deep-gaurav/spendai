package com.spendai.app.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.data.local.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [CategoryRepository.getOrCreate].
 *
 * The dedup rule: lookup by `name.trim().lowercase()`. First time a
 * name is seen, a new row is created with the LLM's emoji. Reusing
 * the same name (case-insensitive) returns the existing row and
 * preserves the original emoji so the look stays consistent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [33])
class CategoryRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: CategoryRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = CategoryRepository(db.categoryDao())
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `getOrCreate inserts a new row on first sight`() = runBlocking {
        val now = 1_000L
        val cat = repo.getOrCreate("Food", "\uD83C\uDF54", now)
        assertEquals("Food", cat.name)
        assertEquals("food", cat.normalizedName)
        assertEquals("\uD83C\uDF54", cat.emoji)
        assertEquals(now, cat.createdAt)
    }

    @Test
    fun `getOrCreate reuses existing row and preserves emoji`() = runBlocking {
        val cat1 = repo.getOrCreate("Food", "\uD83C\uDF54", 1L)
        val cat2 = repo.getOrCreate("Food", "\uD83E\uDD6A", 2L)
        assertEquals(cat1.id, cat2.id)
        assertEquals("\uD83C\uDF54", cat2.emoji)
    }

    @Test
    fun `getOrCreate is case-insensitive on the name`() = runBlocking {
        repo.getOrCreate("Food", "\uD83C\uDF54", 1L)
        val cat = repo.getOrCreate("food", "\uD83E\uDD6A", 2L)
        assertEquals("Food", cat.name)
        assertEquals("\uD83C\uDF54", cat.emoji)
    }

    @Test
    fun `getOrCreate falls back to default emoji when blank`() = runBlocking {
        val cat = repo.getOrCreate("Other", null, 1L)
        assertEquals("\uD83D\uDCB8", cat.emoji)
    }
}
