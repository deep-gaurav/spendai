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
import com.spendai.app.data.local.dao.IngestionLogDao
import com.spendai.app.data.local.dao.MerchantDao
import com.spendai.app.data.local.dao.ParsedSmsDao
import com.spendai.app.data.local.dao.PendingReviewDao
import com.spendai.app.data.local.dao.SmsDao
import com.spendai.app.data.local.dao.TransactionDao
import com.spendai.app.data.local.dao.TransactionLinkDao
import com.spendai.app.data.local.entity.Account
import com.spendai.app.data.local.entity.Category
import com.spendai.app.data.local.entity.FinancialSource
import com.spendai.app.data.local.entity.IngestionLog
import com.spendai.app.data.local.entity.Merchant
import com.spendai.app.data.local.entity.ParsedSms
import com.spendai.app.data.local.entity.PendingReview
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.local.entity.TransactionLink
import com.spendai.app.data.local.migrations.MIGRATION_1_2
import com.spendai.app.data.local.migrations.MIGRATION_2_3

/**
 * The single Room database for SpendAI.
 *
 * Schema is exported to `app/schemas/` (configured in app/build.gradle.kts)
 * so we can diff versions in code review and write migration tests.
 *
 * ## v3 → v5 (no migration)
 *
 * v5 added:
 *  - The new `category` table (dynamic categories created by A2).
 *  - `Transaction.title`, `Transaction.categoryId`.
 *  - `Merchant.categoryId`.
 *  - `Account.colorHex`.
 *
 * The user explicitly chose to wipe app data for this upgrade, so
 * [fallbackToDestructiveMigration] is enabled and no v3 → v5
 * migration is shipped. Any pre-existing user who happens to keep
 * their data will have their database dropped and recreated; any
 * v1 or v2 user still on those schemas runs the existing
 * MIGRATION_1_2 and MIGRATION_2_3 chain first and then hits the
 * destructive fallback for the v3 → v5 step.
 */
@Database(
    entities = [
        RawSmsMessage::class,
        FinancialSource::class,
        ParsedSms::class,
        Account::class,
        Merchant::class,
        Transaction::class,
        TransactionLink::class,
        PendingReview::class,
        IngestionLog::class,
        Category::class,
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun smsDao(): SmsDao
    abstract fun financialSourceDao(): FinancialSourceDao
    abstract fun parsedSmsDao(): ParsedSmsDao
    abstract fun accountDao(): AccountDao
    abstract fun merchantDao(): MerchantDao
    abstract fun transactionDao(): TransactionDao
    abstract fun transactionLinkDao(): TransactionLinkDao
    abstract fun pendingReviewDao(): PendingReviewDao
    abstract fun ingestionLogDao(): IngestionLogDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        private const val DB_NAME = "spendai.db"

        /** All known migrations in order. v3 → v5 is destructive. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        @Volatile
        private var instance: AppDatabase? = null

        /** Double-checked locking singleton. */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
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
