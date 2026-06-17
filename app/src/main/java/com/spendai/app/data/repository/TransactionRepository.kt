package com.spendai.app.data.repository

import com.spendai.app.data.local.dao.TransactionDao
import com.spendai.app.data.local.dao.TransactionDetailsRow
import com.spendai.app.data.local.entity.Transaction
import kotlinx.coroutines.flow.Flow

class TransactionRepository(private val dao: TransactionDao) {
    suspend fun insert(row: Transaction): Long = dao.insert(row)
    suspend fun update(row: Transaction) = dao.update(row)
    suspend fun delete(row: Transaction) = dao.delete(row)
    suspend fun getById(id: Long): Transaction? = dao.getById(id)
    suspend fun getByParsedSms(parsedSmsId: Long): Transaction? = dao.getByParsedSms(parsedSmsId)
    suspend fun getSince(sinceMillis: Long): List<Transaction> = dao.getSince(sinceMillis)
    fun observeAll(): Flow<List<Transaction>> = dao.observeAll()

    /**
     * Hot stream of every transaction joined with its display
     * fields. Powers the home recent activity and the transactions
     * list so the row can render title/emoji/time/account without
     * any extra DB lookups.
     */
    fun observeAllWithDetails(): Flow<List<TransactionDetailsRow>> = dao.observeAllWithDetails()
}
