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

    /**
     * The most-recently-seen [limit] merchants. A2 ships a slice of
     * the merchant table into its prompt bundle so the model can
     * match an incoming SMS to an existing row; capping the slice
     * keeps the prompt comfortably under a 64K total context.
     */
    suspend fun getRecent(limit: Int): List<Merchant> = dao.getRecent(limit)
}
