package com.spendai.app.data.repository

import com.spendai.app.data.local.dao.AccountDao
import com.spendai.app.data.local.entity.Account
import kotlinx.coroutines.flow.Flow

class AccountRepository(private val dao: AccountDao) {
    suspend fun insert(row: Account): Long = dao.insert(row)
    suspend fun getById(id: Long): Account? = dao.getById(id)
    suspend fun getBySource(sourceId: Long): List<Account> = dao.getBySource(sourceId)
    suspend fun getAllOnce(): List<Account> = dao.getAllOnce()
    fun observeAll(): Flow<List<Account>> = dao.observeAll()
    suspend fun findBySourceAndMasked(sourceId: Long, maskedNumber: String): Account? =
        dao.findBySourceAndMasked(sourceId, maskedNumber)
}
