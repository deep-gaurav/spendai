package com.spendai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spendai.app.data.local.entity.ManualCorrection
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [ManualCorrection] rows.
 *
 * The two reads the callers actually use:
 *
 *  - [getRecent]: the A3 prompt loader fetches up to N rows, newest
 *    first, and injects them into the system prompt.
 *  - [observeRecent]: hot stream the linked-SMS / corrections list
 *    UI will eventually want (not yet wired; the table is small
 *    enough that re-querying on screen open is fine for v1).
 *
 * [pruneToMostRecent] is the on-disk size cap. The on-prompt cap is
 * enforced by the caller; this one keeps the table from growing
 * forever.
 */
@Dao
interface ManualCorrectionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: ManualCorrection): Long

    /**
     * Newest [limit] corrections. A3's prompt loader passes the
     * value of [com.spendai.app.data.repository.ManualCorrectionRepository.MAX_INJECTED].
     */
    @Query("SELECT * FROM manual_correction ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ManualCorrection>

    @Query("SELECT * FROM manual_correction ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ManualCorrection>>

    @Query("SELECT * FROM manual_correction WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ManualCorrection?

    @Query("SELECT COUNT(*) FROM manual_correction")
    suspend fun count(): Int

    /**
     * Keep the most recent [keep] rows. Called at the end of each
     * ingestion run, mirroring [com.spendai.app.data.local.dao.IngestionLogDao.pruneToMostRecent].
     */
    @Query(
        "DELETE FROM manual_correction WHERE id NOT IN " +
            "(SELECT id FROM manual_correction ORDER BY createdAt DESC LIMIT :keep)"
    )
    suspend fun pruneToMostRecent(keep: Int)
}
