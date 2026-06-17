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

    suspend fun unparsedOnce(): List<RawSmsMessage> =
        dao.getByStatusOnce(SmsStatus.UNPARSED)

    suspend fun unparsedInRange(startMillis: Long, endMillis: Long): List<RawSmsMessage> =
        dao.getByStatusInRangeOnce(
            SmsStatus.UNPARSED, startMillis, endMillis,
        )

    /**
     * Every `raw_sms` row that does not have a corresponding
     * `spend_transaction`. This is the input to the
     * `IngestionPipeline.runPending` path.
     */
    suspend fun pendingNotCommitted(): List<RawSmsMessage> = dao.getPendingNotCommitted()

    fun observeUnparsed(): Flow<List<RawSmsMessage>> =
        dao.observeByStatus(SmsStatus.UNPARSED)

    suspend fun markParsed(id: Long) = dao.updateStatus(id, SmsStatus.PARSED)
    suspend fun markIgnored(id: Long) = dao.updateStatus(id, SmsStatus.IGNORED)
    suspend fun pendingCount(): Int = dao.countByStatus(SmsStatus.UNPARSED)

    /** Backfill the [RawSmsMessage.parsedSmsId] after Agent 1 succeeds. */
    suspend fun setParsedSmsId(rawSmsId: Long, parsedSmsId: Long) =
        dao.setParsedSmsId(rawSmsId, parsedSmsId)
}
