package com.spendai.app.data.repository

import com.spendai.app.data.local.dao.SmsDao
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SmsStatus
import kotlinx.coroutines.flow.Flow

/**
 * Thin facade over [SmsDao] for the receiver and worker to consume.
 * Keeping this layer in place gives us one obvious spot to add caching,
 * in-memory deduping, or an in-process channel later.
 */
class SmsRepository(private val dao: SmsDao) {

    suspend fun insert(message: RawSmsMessage): Long = dao.insert(message)
    suspend fun getById(id: Long): RawSmsMessage? = dao.getById(id)

    /**
     * Legacy UNPARSED scan. Prefer [pendingInRange] or [pendingOnce]
     * for new code — this one is kept for tests and the debug log
     * "list unparsed" view.
     */
    suspend fun unparsedOnce(): List<RawSmsMessage> =
        dao.getByStatusOnce(SmsStatus.UNPARSED)

    suspend fun unparsedInRange(startMillis: Long, endMillis: Long): List<RawSmsMessage> =
        dao.getByStatusInRangeOnce(
            SmsStatus.UNPARSED, startMillis, endMillis,
        )

    /**
     * Pending rows in `[startMillis, endMillis)`. The v6 idempotency
     * query — a row is pending iff `status = UNPARSED AND processedAt
     * IS NULL`. Use this from the foreground service's "ingest this
     * range" path.
     */
    suspend fun pendingInRange(startMillis: Long, endMillis: Long): List<RawSmsMessage> =
        dao.getPendingInRange(startMillis, endMillis)

    /**
     * Every pending row, ignoring range. Used by the service's
     * "re-process pending" path and by the periodic worker.
     */
    suspend fun pendingOnce(): List<RawSmsMessage> = dao.getPendingOnce()

    fun observeUnparsed(): Flow<List<RawSmsMessage>> =
        dao.observeByStatus(SmsStatus.UNPARSED)

    /**
     * Terminal state transition: row was just committed.
     * Sets `status = PARSED`, `processedAt = now`, clears `lastError`.
     */
    suspend fun markProcessed(id: Long, processedAt: Long = System.currentTimeMillis()) =
        dao.markProcessed(id, processedAt)

    /**
     * Terminal state transition: row was just marked IGNORE by A1.
     * Sets `status = IGNORED`, `processedAt = now`, clears `lastError`.
     */
    suspend fun markIgnoredProcessed(id: Long, processedAt: Long = System.currentTimeMillis()) =
        dao.markIgnoredProcessed(id, processedAt)

    /**
     * Soft state transition: row was just skipped with an error.
     * Keeps `status = UNPARSED` and `processedAt = NULL` so a
     * future run picks it up. Writes `lastError` for the debug log.
     */
    suspend fun markSkipped(id: Long, error: String) = dao.markSkipped(id, error)

    /** Backwards-compatible alias — same as [markProcessed] in v6. */
    suspend fun markParsed(id: Long) = dao.markProcessed(id, System.currentTimeMillis())

    /** Backwards-compatible alias — same as [markIgnoredProcessed] in v6. */
    suspend fun markIgnored(id: Long) = dao.markIgnoredProcessed(id, System.currentTimeMillis())

    suspend fun pendingCount(): Int = dao.countByStatus(SmsStatus.UNPARSED)

    /** Backfill the [RawSmsMessage.parsedSmsId] after Agent 1 succeeds. */
    suspend fun setParsedSmsId(rawSmsId: Long, parsedSmsId: Long) =
        dao.setParsedSmsId(rawSmsId, parsedSmsId)
}
