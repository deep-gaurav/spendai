package com.spendai.app.data.repository

import com.spendai.app.data.local.dao.TransactionDao
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
}
