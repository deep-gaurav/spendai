package com.spendai.app.data.local.migrations

import android.content.ContentValues
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.ParsedSms
import com.spendai.app.data.local.entity.SmsStatus
import com.spendai.app.data.local.entity.SourceInstrumentType
import com.spendai.app.data.local.entity.SourceStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks down the v1 → v2 schema migration. Failure here is the
 * canary for "we just lost user data on a schema bump".
 *
 * Strategy (avoids Room 2.6's Instrumentation-only MigrationTestHelper):
 *  1. Use a [SupportSQLiteOpenHelper] with a v1 callback to
 *     create a v1-shape database and insert a row in each of the
 *     two original tables.
 *  2. Open the same file with Room + MIGRATION_1_2 — Room runs
 *     the migration as part of normal open.
 *  3. Assert via the DAOs that the v1 row survived and that all
 *     new tables exist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [33])
class Migration1To2Test {

    private val dbName = "migration-test.db"
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val factory = FrameworkSQLiteOpenHelperFactory()

    @Before
    fun cleanState() {
        ctx.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    @Test
    fun migrate1To2_preservesRowsAndAppliesDefaults() = runTest {
        // 1. Open with v1 schema (raw SQL) and seed rows.
        val v1Helper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(dbName)
                .callback(V1Callback())
                .build()
        )
        v1Helper.writableDatabase.use { db ->
            db.insert(
                "raw_sms",
                android.database.sqlite.SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("senderAddress", "VK-HDFCBK")
                    put("msgBody", "Rs 250 at ZOMATO")
                    put("timestamp", 1_700_000_000_000L)
                    put("status", "UNPARSED")
                }
            )
            db.insert(
                "financial_source",
                android.database.sqlite.SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("sourceKey", "Bank_HDFCBK")
                    put("deducedType", "CREDIT_CARD")
                    put("firstSeenTimestamp", 1_700_000_000_000L)
                }
            )
        }

        // 2. Open with Room + migration. Room runs MIGRATION_1_2.
        Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2)
            .build()
            .also { db ->
            try {                // raw_sms row preserved + parsedSmsId defaults to null
                val sms = db.smsDao().getByStatusOnce(SmsStatus.UNPARSED)
                assertEquals(1, sms.size)
                assertEquals("VK-HDFCBK", sms[0].senderAddress)
                assertEquals("Rs 250 at ZOMATO", sms[0].msgBody)
                assertNull(sms[0].parsedSmsId)

                // financial_source: new columns have sensible defaults
                val sources = db.financialSourceDao().getAllOnce()
                assertEquals(1, sources.size)
                val src = sources[0]
                assertEquals("Bank_HDFCBK", src.sourceKey)
                assertEquals("CREDIT_CARD", src.deducedType)
                assertNull(src.displayName)
                assertNull(src.bankName)
                assertNull(src.accountLast4)
                assertEquals(SourceInstrumentType.UNKNOWN.name, src.instrumentType)
                assertEquals(SourceStatus.NEEDS_REVIEW.name, src.status)
                assertNull(src.confirmedAt)

                // New tables exist and are empty.
                assertTrue(db.accountDao().getAllOnce().isEmpty())
                assertTrue(db.merchantDao().getAllOnce().isEmpty())
                assertTrue(db.transactionLinkDao().getAllOnce().isEmpty())            } finally {
                db.close()
            }
            }
    }

    @Test
    fun migrate1To2_createsExpectedIndexes() = runTest {
        factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(dbName)
                .callback(V1Callback())
                .build()
        ).writableDatabase.close()

        Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2)
            .build()
            .also { db ->
            try {                val expected = listOf(
                    "index_parsed_sms_rawSmsId",
                    "index_account_sourceId_maskedNumber",
                    "index_merchant_normalizedName",
                    "index_pending_review_kind_resolvedAt",
                    "index_raw_sms_parsedSmsId",
                )
                val found = mutableListOf<String>()
                db.openHelper.readableDatabase.query(
                    "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'"
                ).use { c ->
                    while (c.moveToNext()) found += c.getString(0)
                }
                for (e in expected) {
                    assertTrue("missing index: $e (have: $found)", found.contains(e))
                }            } finally {
                db.close()
            }
            }
    }

    @Test
    fun rawSmsRow_canBeLinkedToNewlyInsertedParsedSms() = runTest {
        factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(dbName)
                .callback(V1Callback())
                .build()
        ).writableDatabase.use { db ->
            db.insert(
                "raw_sms",
                android.database.sqlite.SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("senderAddress", "VK-ICICIB")
                    put("msgBody", "debit test")
                    put("timestamp", 1L)
                    put("status", "UNPARSED")
                }
            )
        }
        Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2)
            .build()
            .also { db ->
            try {                val smsRow = db.smsDao().getByStatusOnce(SmsStatus.UNPARSED).first()
                val parsedId = db.parsedSmsDao().insert(
                    ParsedSms(
                        rawSmsId = smsRow.id,
                        parsedAt = 2L,
                        kind = "TRANSACTION",
                        amountPaise = 100L,
                        a1Confidence = 0.9f,
                        a1RawJson = "{}",
                    )
                )
                db.smsDao().setParsedSmsId(smsRow.id, parsedId)
                val reloaded = db.smsDao().getByStatusOnce(SmsStatus.UNPARSED).first()
                assertEquals(parsedId, reloaded.parsedSmsId)            } finally {
                db.close()
            }
            }
    }

    /**
     * Minimal v1 callback. Mirrors the v1 schema from
     * `app/schemas/com.spendai.app.data.local.AppDatabase/1.json`
     * so we can create a real v1-shape database to migrate from.
     */
    private class V1Callback : SupportSQLiteOpenHelper.Callback(1) {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE `raw_sms` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`senderAddress` TEXT NOT NULL, " +
                    "`msgBody` TEXT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, " +
                    "`status` TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE INDEX `index_raw_sms_status` ON `raw_sms` (`status`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX `index_raw_sms_senderAddress_timestamp` " +
                    "ON `raw_sms` (`senderAddress`, `timestamp`)"
            )
            db.execSQL(
                "CREATE TABLE `financial_source` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`sourceKey` TEXT NOT NULL, " +
                    "`deducedType` TEXT NOT NULL, " +
                    "`userLabel` TEXT, " +
                    "`firstSeenTimestamp` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX `index_financial_source_sourceKey` " +
                    "ON `financial_source` (`sourceKey`)"
            )
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 only — no upgrades.
        }
    }
}
