package com.spendai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spendai.app.data.local.entity.IngestionLog
import kotlinx.coroutines.flow.Flow

@Dao
interface IngestionLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: IngestionLog): Long

    @Query("SELECT * FROM ingestion_log ORDER BY ingestedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<IngestionLog>

    @Query("SELECT * FROM ingestion_log ORDER BY ingestedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<IngestionLog>>

    @Query("SELECT * FROM ingestion_log WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): IngestionLog?

    @Query("SELECT COUNT(*) FROM ingestion_log")
    suspend fun count(): Int

    /**
     * Keep the most recent [keep] rows. Pruning happens at the end
     * of each pipeline run so the audit table doesn't grow
     * unbounded.
     */
    @Query(
        "DELETE FROM ingestion_log WHERE id NOT IN " +
            "(SELECT id FROM ingestion_log ORDER BY ingestedAt DESC LIMIT :keep)"
    )
    suspend fun pruneToMostRecent(keep: Int)
}
