package com.spendai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spendai.app.data.local.entity.RepromptJob
import com.spendai.app.data.local.entity.RepromptJobStatus
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [RepromptJob] rows.
 *
 * The two reads the service actually uses:
 *
 *  - [getStalePendingOrRunning]: cold-start scan. Returns rows that
 *    are still PENDING or RUNNING but whose [RepromptJob.lastAttemptAt]
 *    is older than the supplied threshold. The service re-drives
 *    these on `onCreate` so a process death does not drop a job.
 *  - [observeByTransactionId]: hot stream the edit screen uses to
 *    render the "Reprompting…" banner even if the user navigated
 *    away and back.
 */
@Dao
interface RepromptJobDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: RepromptJob): Long

    @Query("SELECT * FROM reprompt_job WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): RepromptJob?

    @Query(
        "SELECT * FROM reprompt_job WHERE transactionId = :transactionId " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    suspend fun getLatestByTransactionId(transactionId: Long): RepromptJob?

    @Query(
        "SELECT * FROM reprompt_job WHERE transactionId = :transactionId " +
            "ORDER BY createdAt DESC"
    )
    fun observeByTransactionId(transactionId: Long): Flow<List<RepromptJob>>

    /**
     * Rows still in PENDING or RUNNING that are older than
     * [staleBeforeMillis] (epoch millis). Used by the cold-start
     * scan in the service to recover from process death.
     */
    @Query(
        "SELECT * FROM reprompt_job " +
            "WHERE (status = 'PENDING' OR status = 'RUNNING') " +
            "AND (lastAttemptAt IS NULL OR lastAttemptAt < :staleBeforeMillis) " +
            "ORDER BY createdAt ASC"
    )
    suspend fun getStalePendingOrRunning(staleBeforeMillis: Long): List<RepromptJob>

    @Query(
        "UPDATE reprompt_job SET status = :status, attemptCount = :attemptCount, " +
            "lastAttemptAt = :lastAttemptAt, errorMessage = :errorMessage " +
            "WHERE id = :id"
    )
    suspend fun updateAttempt(
        id: Long,
        status: String,
        attemptCount: Int,
        lastAttemptAt: Long,
        errorMessage: String?,
    )

    @Query(
        "UPDATE reprompt_job SET status = :status, completedAt = :completedAt, " +
            "errorMessage = :errorMessage WHERE id = :id"
    )
    suspend fun complete(
        id: Long,
        status: String,
        completedAt: Long,
        errorMessage: String?,
    )

    @Query("SELECT COUNT(*) FROM reprompt_job")
    suspend fun count(): Int

    /**
     * Keep the most recent [keep] rows. Mirrors the pruning
     * strategy used by [IngestionLogDao] and [ManualCorrectionDao]
     * so the audit table does not grow unbounded.
     */
    @Query(
        "DELETE FROM reprompt_job WHERE id NOT IN " +
            "(SELECT id FROM reprompt_job ORDER BY createdAt DESC LIMIT :keep)"
    )
    suspend fun pruneToMostRecent(keep: Int)

    @Query(
        "SELECT * FROM reprompt_job WHERE status = :status ORDER BY createdAt ASC"
    )
    suspend fun getByStatus(status: String = RepromptJobStatus.RUNNING.name): List<RepromptJob>
}
