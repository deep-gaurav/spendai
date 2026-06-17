package com.spendai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SmsStatus
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for raw SMS rows.
 *
 * All `suspend` functions are dispatched on `Dispatchers.IO` internally by
 * Room's coroutine adapter — callers do NOT need to wrap them.
 *
 * Conflict strategy: [OnConflictStrategy.IGNORE] so the unique
 * `(senderAddress, timestamp)` index silently dedupes dual-SIM re-deliveries
 * instead of crashing the receiver.
 *
 * ## Idempotency (v6)
 *
 * The pipeline uses the `processedAt` column to know "this row is
 * done, leave it alone". The `getPendingInRange` query filters on
 * `status = UNPARSED AND processedAt IS NULL` so a single indexed
 * scan returns exactly the rows that still need work, regardless of
 * whether they are pre- or post-IGNORE.
 */
@Dao
interface SmsDao {

    /**
     * Inserts a raw SMS. If the (sender, timestamp) pair already exists,
     * the existing row is left alone and the insert returns -1.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: RawSmsMessage): Long

    /** One-shot read used by the worker; not a Flow. */
    @Query("SELECT * FROM raw_sms WHERE status = :status ORDER BY timestamp ASC")
    suspend fun getByStatusOnce(status: SmsStatus = SmsStatus.UNPARSED): List<RawSmsMessage>

    /** Hot stream for any future UI surface. */
    @Query("SELECT * FROM raw_sms WHERE status = :status ORDER BY timestamp ASC")
    fun observeByStatus(status: SmsStatus = SmsStatus.UNPARSED): Flow<List<RawSmsMessage>>

    @Query("UPDATE raw_sms SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: SmsStatus)

    /**
     * Mark a row terminally processed. Sets `status = PARSED` and
     * `processedAt = :processedAt` in a single UPDATE. Clears
     * `lastError` so a successful retry wipes a previous failure.
     */
    @Query(
        "UPDATE raw_sms SET status = 'PARSED', processedAt = :processedAt, " +
            "lastError = NULL WHERE id = :id"
    )
    suspend fun markProcessed(id: Long, processedAt: Long)

    /**
     * Mark a row as ignored. Sets `status = IGNORED` and
     * `processedAt = :processedAt` in a single UPDATE. An IGNORE
     * is a terminal state — the row will not be re-picked by the
     * pending query. Clears `lastError` defensively.
     */
    @Query(
        "UPDATE raw_sms SET status = 'IGNORED', processedAt = :processedAt, " +
            "lastError = NULL WHERE id = :id"
    )
    suspend fun markIgnoredProcessed(id: Long, processedAt: Long)

    /**
     * Mark a row as skipped with a human-readable reason. Keeps
     * `status = UNPARSED` and `processedAt = NULL` so a future
     * run picks it up. Writes `lastError` for the debug log.
     */
    @Query(
        "UPDATE raw_sms SET lastError = :error WHERE id = :id"
    )
    suspend fun markSkipped(id: Long, error: String)

    @Query("UPDATE raw_sms SET parsedSmsId = :parsedSmsId WHERE id = :id")
    suspend fun setParsedSmsId(id: Long, parsedSmsId: Long)

    @Query("SELECT COUNT(*) FROM raw_sms WHERE status = :status")
    suspend fun countByStatus(status: SmsStatus): Int

    @Query("SELECT * FROM raw_sms WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): RawSmsMessage?

    @Query("SELECT * FROM raw_sms WHERE status = :status AND timestamp >= :startMillis AND timestamp < :endMillis ORDER BY timestamp ASC")
    suspend fun getByStatusInRangeOnce(
        status: SmsStatus = SmsStatus.UNPARSED,
        startMillis: Long,
        endMillis: Long,
    ): List<RawSmsMessage>

    /**
     * Pending rows in `[startMillis, endMillis)` — the v6 idempotency
     * query. A row is pending iff `status = UNPARSED AND processedAt IS NULL`,
     * regardless of whether a previous run wrote a `parsed_sms` row
     * and lost the A2 commit (the cache hit path in [com.spendai.app.domain.ingestion.IngestionPipeline]
     * handles those). Backed by the
     * `(status, processedAt, timestamp)` composite index.
     */
    @Query(
        "SELECT * FROM raw_sms " +
            "WHERE status = 'UNPARSED' AND processedAt IS NULL " +
            "AND timestamp >= :startMillis AND timestamp < :endMillis " +
            "ORDER BY timestamp ASC"
    )
    suspend fun getPendingInRange(startMillis: Long, endMillis: Long): List<RawSmsMessage>

    /**
     * All `raw_sms` rows that still need work, ignoring range.
     * Used by the "Re-process pending" CTA — covers every row whose
     * `processedAt` is still null.
     */
    @Query(
        "SELECT * FROM raw_sms " +
            "WHERE status = 'UNPARSED' AND processedAt IS NULL " +
            "ORDER BY timestamp ASC"
    )
    suspend fun getPendingOnce(): List<RawSmsMessage>
}
