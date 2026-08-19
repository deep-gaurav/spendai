package com.spendai.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import com.spendai.app.data.local.dao.AccountDao
import com.spendai.app.data.local.dao.CategoryDao
import com.spendai.app.data.local.dao.FinancialSourceDao
import com.spendai.app.data.local.dao.InsightsDao
import com.spendai.app.data.local.dao.IngestionLogDao
import com.spendai.app.data.local.dao.LinkedSmsDao
import com.spendai.app.data.local.dao.ManualCorrectionDao
import com.spendai.app.data.local.dao.MerchantDao
import com.spendai.app.data.local.dao.MerchantMetadataDao
import com.spendai.app.data.local.dao.MonthlySnapshotDao
import com.spendai.app.data.local.dao.ParsedSmsDao
import com.spendai.app.data.local.dao.PendingReviewDao
import com.spendai.app.data.local.dao.RepromptJobDao
import com.spendai.app.data.local.dao.SmsDao
import com.spendai.app.data.local.dao.TransactionDao
import com.spendai.app.data.local.dao.TransactionLinkDao
import com.spendai.app.data.local.entity.Account
import com.spendai.app.data.local.entity.Category
import com.spendai.app.data.local.entity.FinancialSource
import com.spendai.app.data.local.entity.IngestionLog
import com.spendai.app.data.local.entity.ManualCorrection
import com.spendai.app.data.local.entity.Merchant
import com.spendai.app.data.local.entity.MerchantMetadata
import com.spendai.app.data.local.entity.MonthlySnapshot
import com.spendai.app.data.local.entity.ParsedSms
import com.spendai.app.data.local.entity.PendingReview
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.RepromptJob
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.local.entity.TransactionLink
import com.spendai.app.data.local.migrations.MIGRATION_1_2
import com.spendai.app.data.local.migrations.MIGRATION_2_3
import com.spendai.app.data.local.migrations.MIGRATION_5_6
import com.spendai.app.data.local.migrations.MIGRATION_6_7
import com.spendai.app.data.local.migrations.MIGRATION_7_8
import com.spendai.app.data.local.migrations.MIGRATION_8_9
import com.spendai.app.data.local.migrations.MIGRATION_9_10
import java.io.File

/**
 * The single Room database for SpendAI.
 *
 * Schema is exported to `app/schemas/` (configured in app/build.gradle.kts)
 * so we can diff versions in code review and write migration tests.
 *
 * ## v3 -> v5 (no migration)
 *
 * v5 added:
 *  - The new `category` table (dynamic categories created by A2).
 *  - `Transaction.title`, `Transaction.categoryId`.
 *  - `Merchant.categoryId`.
 *  - `Account.colorHex`.
 *
 * The user explicitly chose to wipe app data for this upgrade, so
 * [fallbackToDestructiveMigration] is enabled and no v3 -> v5
 * migration is shipped. Any pre-existing user who happens to keep
 * their data will have their database dropped and recreated; any
 * v1 or v2 user still on those schemas runs the existing
 * MIGRATION_1_2 and MIGRATION_2_3 chain first and then hits the
 * destructive fallback for the v3 -> v5 step.
 *
 * ## v5 -> v6
 *
 * Adds `raw_sms.processedAt` and `raw_sms.lastError` for
 * idempotency. See MIGRATION_5_6 for the column adds and the
 * supporting composite index.
 *
 * ## v6 -> v7
 *
 * Adds the `manual_correction` table and an `ingestion_log.userPrompt`
 * column for the reprompt lesson-injection flow.
 *
 * ## v7 -> v8
 *
 * Adds the `reprompt_job` table for durable execution tracking of
 * A3 reprompts. See MIGRATION_7_8.
 *
 * ## v8 -> v9
 *
 * Adds `merchant.isSelf` and the `merchant_metadata` table for
 * user-defined merchant knowledge (counterparty-is-me, freeform
 * notes, category hints). The InsightsDao exclusion predicate
 * now also drops transactions whose merchant has `isSelf = 1`.
 *
 * ## v9 -> v10
 *
 * Adds the `monthly_snapshot` table — the local backup for the
 * Tracking screen's month-wise history. See MIGRATION_9_10.
 */
@Database(
    entities = [
        RawSmsMessage::class,
        FinancialSource::class,
        ParsedSms::class,
        Account::class,
        Merchant::class,
        MerchantMetadata::class,
        Transaction::class,
        TransactionLink::class,
        PendingReview::class,
        IngestionLog::class,
        Category::class,
        ManualCorrection::class,
        RepromptJob::class,
        MonthlySnapshot::class,
    ],
    version = 10,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun smsDao(): SmsDao
    abstract fun financialSourceDao(): FinancialSourceDao
    abstract fun parsedSmsDao(): ParsedSmsDao
    abstract fun accountDao(): AccountDao
    abstract fun merchantDao(): MerchantDao
    abstract fun merchantMetadataDao(): MerchantMetadataDao
    abstract fun transactionDao(): TransactionDao
    abstract fun transactionLinkDao(): TransactionLinkDao
    abstract fun pendingReviewDao(): PendingReviewDao
    abstract fun ingestionLogDao(): IngestionLogDao
    abstract fun manualCorrectionDao(): ManualCorrectionDao
    abstract fun linkedSmsDao(): LinkedSmsDao
    abstract fun categoryDao(): CategoryDao
    abstract fun insightsDao(): InsightsDao
    abstract fun repromptJobDao(): RepromptJobDao
    abstract fun monthlySnapshotDao(): MonthlySnapshotDao

    companion object {
        private const val DB_NAME = "spendai.db"

        /** All known migrations in order. v3 -> v5 is destructive. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
        )

        @Volatile
        private var instance: AppDatabase? = null

        /** Double-checked locking singleton. */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        /** The on-disk location of [DB_NAME], for callers that need to copy the raw file (see `FullBackupManager`). */
        fun databaseFile(context: Context): File = context.getDatabasePath(DB_NAME)

        /**
         * Releases Room's open handle on the database and clears the
         * singleton so the next [get] reopens from disk. Only meant
         * for the full-data-restore flow, which overwrites [DB_NAME]
         * out from under Room and therefore cannot keep the old
         * handle alive — callers must not touch the DB again after
         * this until the process has restarted (see `AppRestarter`),
         * since every other singleton in `SpendAiApp` still holds a
         * DAO derived from the now-closed instance.
         */
        @Synchronized
        fun closeAndReset() {
            instance?.close()
            instance = null
        }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME
            )
                .addMigrations(*ALL_MIGRATIONS)
                .fallbackToDestructiveMigration()
                .build()
    }
}
