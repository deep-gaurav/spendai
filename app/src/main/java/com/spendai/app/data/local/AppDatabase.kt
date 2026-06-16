package com.spendai.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.spendai.app.data.local.dao.FinancialSourceDao
import com.spendai.app.data.local.dao.SmsDao
import com.spendai.app.data.local.entity.FinancialSource
import com.spendai.app.data.local.entity.RawSmsMessage

/**
 * The single Room database for SpendAI Phase 1.
 *
 * Schema is exported to `app/schemas/` (configured in app/build.gradle.kts)
 * so we can diff versions in code review and write migration tests later.
 *
 * `fallbackToDestructiveMigration` is appropriate for v1 because there are
 * no users yet. We will write explicit migrations from v1 onwards — losing
 * user data on a v1 → v2 schema bump would be a privacy bug, not a feature.
 */
@Database(
    entities = [RawSmsMessage::class, FinancialSource::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun smsDao(): SmsDao
    abstract fun financialSourceDao(): FinancialSourceDao

    companion object {
        private const val DB_NAME = "spendai.db"

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
                .fallbackToDestructiveMigration()
                .build()
    }
}
