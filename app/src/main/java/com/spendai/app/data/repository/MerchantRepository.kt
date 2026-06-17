package com.spendai.app.data.repository

import com.spendai.app.data.local.dao.MerchantDao
import com.spendai.app.data.local.entity.Merchant
import kotlinx.coroutines.flow.Flow

class MerchantRepository(private val dao: MerchantDao) {
    suspend fun insert(row: Merchant): Long = dao.insertIgnore(row)
    suspend fun getById(id: Long): Merchant? = dao.getById(id)
    suspend fun findByNormalizedName(name: String): Merchant? = dao.findByNormalizedName(name)
    suspend fun findByVpa(vpa: String): Merchant? = dao.findByVpa(vpa)
    suspend fun getAllOnce(): List<Merchant> = dao.getAllOnce()
    fun observeAll(): Flow<List<Merchant>> = dao.observeAll()
}
