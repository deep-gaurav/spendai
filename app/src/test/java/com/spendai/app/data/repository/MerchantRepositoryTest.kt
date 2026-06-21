package com.spendai.app.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.Account
import com.spendai.app.data.local.entity.Category
import com.spendai.app.data.local.entity.FinancialSource
import com.spendai.app.data.local.entity.Merchant
import com.spendai.app.data.local.entity.MerchantMetadata
import com.spendai.app.data.local.entity.MerchantMetadataKind
import com.spendai.app.data.local.entity.ParsedSms
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SourceInstrumentType
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.local.entity.TransactionDirection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
 * Pure-JVM (Robolectric) tests for [MerchantRepository].
 *
 * The behaviours we care about for the v9 metadata work:
 *  - `setIsSelf` flips the boolean and reads back; subsequent
 *    `getById` reflects the new value.
 *  - `putMetadata` is upsert: writing a NOTE twice keeps a
 *    single row with the new value and the old `createdAt`
 *    (so the ripple downstream doesn't get a new timestamp).
 *  - `removeMetadata` deletes only the matching `(merchantId,
 *    kind)` pair; other kinds and other merchants are
 *    untouched.
 *  - `getMetadataForMerchants` returns the metadata for the
 *    given merchant ids in a single query, empty when no
 *    matches.
 *  - Cascade: deleting a merchant removes its metadata rows
 *    (FK ON DELETE CASCADE).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = com.spendai.app.TestApp::class, sdk = [33])
class MerchantRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: MerchantRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = MerchantRepository(db.merchantDao(), db.merchantMetadataDao())
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `setIsSelf flips the boolean`() = runBlocking {
        val id = repo.insert(
            Merchant(name = "OWN ACCOUNT", normalizedName = "own account", firstSeenAt = 1L)
        )
        assertEquals(false, repo.getById(id)?.isSelf)
        repo.setIsSelf(id, true)
        assertEquals(true, repo.getById(id)?.isSelf)
        repo.setIsSelf(id, false)
        assertEquals(false, repo.getById(id)?.isSelf)
    }

    @Test
    fun `putMetadata upserts on (merchantId, kind)`() = runBlocking {
        val id = repo.insert(
            Merchant(name = "VENDOR XYZ", normalizedName = "vendor xyz", firstSeenAt = 1L)
        )
        val first = repo.putMetadata(id, MerchantMetadataKind.NOTE, "first", now = 100L)
        assertTrue(first > 0L)
        val firstCreatedAt = repo.getMetadata(id).single().createdAt
        // Re-save a different value: should keep the original createdAt
        val second = repo.putMetadata(id, MerchantMetadataKind.NOTE, "second", now = 200L)
        val rows = repo.getMetadata(id)
        assertEquals(1, rows.size)
        assertEquals("second", rows.single().value)
        assertEquals(firstCreatedAt, rows.single().createdAt)
        // Different kind creates a new row
        repo.putMetadata(id, MerchantMetadataKind.CATEGORY_HINT, "Food", now = 300L)
        assertEquals(2, repo.getMetadata(id).size)
    }

    @Test
    fun `removeMetadata deletes only the matching kind`() = runBlocking {
        val id = repo.insert(
            Merchant(name = "X", normalizedName = "x", firstSeenAt = 1L)
        )
        repo.putMetadata(id, MerchantMetadataKind.NOTE, "n", now = 1L)
        repo.putMetadata(id, MerchantMetadataKind.LABEL, "l", now = 2L)
        assertEquals(2, repo.getMetadata(id).size)
        repo.removeMetadata(id, MerchantMetadataKind.NOTE)
        val remaining = repo.getMetadata(id)
        assertEquals(1, remaining.size)
        assertEquals(MerchantMetadataKind.LABEL.name, remaining.single().kind)
    }

    @Test
    fun `getMetadataForMerchants returns rows for the given ids only`() = runBlocking {
        val a = repo.insert(Merchant(name = "A", normalizedName = "a", firstSeenAt = 1L))
        val b = repo.insert(Merchant(name = "B", normalizedName = "b", firstSeenAt = 2L))
        val c = repo.insert(Merchant(name = "C", normalizedName = "c", firstSeenAt = 3L))
        repo.putMetadata(a, MerchantMetadataKind.NOTE, "an", now = 1L)
        repo.putMetadata(b, MerchantMetadataKind.LABEL, "bl", now = 2L)
        repo.putMetadata(c, MerchantMetadataKind.CATEGORY_HINT, "cch", now = 3L)
        val rows = repo.getMetadataForMerchants(listOf(a, b))
        assertEquals(2, rows.size)
        val kinds = rows.map { it.kind }.toSet()
        assertTrue(kinds.containsAll(listOf("NOTE", "LABEL")))
        // empty list -> empty result
        assertEquals(emptyList<MerchantMetadata>(), repo.getMetadataForMerchants(emptyList()))
    }

    @Test
    fun `cascade on merchant delete removes metadata`() = runBlocking {
        val a = repo.insert(Merchant(name = "A", normalizedName = "a", firstSeenAt = 1L))
        repo.putMetadata(a, MerchantMetadataKind.NOTE, "n", now = 1L)
        assertEquals(1, repo.getMetadata(a).size)
        db.merchantDao().delete(db.merchantDao().getById(a)!!)
        assertEquals(emptyList<MerchantMetadata>(), repo.getMetadata(a))
    }
}
