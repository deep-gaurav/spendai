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

    suspend fun unparsedOnce(): List<RawSmsMessage> =
        dao.getByStatusOnce(SmsStatus.UNPARSED)

    fun observeUnparsed(): Flow<List<RawSmsMessage>> =
        dao.observeByStatus(SmsStatus.UNPARSED)

    suspend fun markParsed(id: Long) = dao.updateStatus(id, SmsStatus.PARSED)
    suspend fun markIgnored(id: Long) = dao.updateStatus(id, SmsStatus.IGNORED)
    suspend fun pendingCount(): Int = dao.countByStatus(SmsStatus.UNPARSED)
}
