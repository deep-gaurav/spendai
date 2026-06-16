package com.spendai.app.data.repository

import com.spendai.app.data.local.dao.FinancialSourceDao
import com.spendai.app.data.local.entity.FinancialSource
import kotlinx.coroutines.flow.Flow

/**
 * Repository for known financial SMS senders. The worker calls [getByKey]
 * on each new message to decide whether the sender has been seen before
 * (and therefore whether to re-classify it or just append the message).
 */
class FinancialSourceRepository(private val dao: FinancialSourceDao) {

    suspend fun upsert(source: FinancialSource): Long = dao.upsert(source)

    suspend fun findByKey(key: String): FinancialSource? = dao.getByKey(key)

    suspend fun allOnce(): List<FinancialSource> = dao.getAllOnce()

    fun observeAll(): Flow<List<FinancialSource>> = dao.observeAll()
}
