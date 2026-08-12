package com.spendai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spendai.app.data.local.entity.MonthlySnapshot
import kotlinx.coroutines.flow.Flow

@Dao
interface MonthlySnapshotDao {

    /**
     * Replaces on ([yearMonth], [currency]) conflict — the write-
     * through backup from the Tracking screen calls this on every
     * recompute, so the latest totals always win.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<MonthlySnapshot>)

    @Query("SELECT * FROM monthly_snapshot ORDER BY yearMonth DESC")
    fun observeAll(): Flow<List<MonthlySnapshot>>

    @Query("SELECT * FROM monthly_snapshot ORDER BY yearMonth DESC")
    suspend fun getAllOnce(): List<MonthlySnapshot>
}
