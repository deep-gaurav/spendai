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
     * All `raw_sms` rows that don't have a corresponding
     * `spend_transaction.rawSmsId` — the "stuck" rows that the
     * Re-process pending CTA picks up. Covers UNPARSED, IGNORED,
     * and PARSED-without-txn.
     */
    @Query(
        """
        SELECT * FROM raw_sms
        WHERE id NOT IN (SELECT rawSmsId FROM spend_transaction)
        ORDER BY timestamp ASC
        """
    )
    suspend fun getPendingNotCommitted(): List<RawSmsMessage>
}
