package com.spendai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spendai.app.data.local.entity.FinancialSource
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for known financial SMS senders.
 *
 * `upsert` uses [OnConflictStrategy.REPLACE] on the `sourceKey` unique
 * index so a re-encounter of a sender refreshes `firstSeenTimestamp` /
 * `deducedType` without producing duplicate rows.
 */
@Dao
interface FinancialSourceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: FinancialSource): Long

    @Query("SELECT * FROM financial_source WHERE sourceKey = :key LIMIT 1")
    suspend fun getByKey(key: String): FinancialSource?

    @Query("SELECT * FROM financial_source ORDER BY firstSeenTimestamp DESC")
    suspend fun getAllOnce(): List<FinancialSource>

    @Query("SELECT * FROM financial_source ORDER BY firstSeenTimestamp DESC")
    fun observeAll(): Flow<List<FinancialSource>>
}
