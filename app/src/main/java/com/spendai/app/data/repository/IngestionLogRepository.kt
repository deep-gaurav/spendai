package com.spendai.app.data.repository

import com.spendai.app.data.local.dao.IngestionLogDao
import com.spendai.app.data.local.entity.IngestionLog
import kotlinx.coroutines.flow.Flow

class IngestionLogRepository(private val dao: IngestionLogDao) {
    suspend fun insert(row: IngestionLog): Long = dao.insert(row)
    suspend fun getRecent(limit: Int = DEFAULT_RECENT): List<IngestionLog> = dao.getRecent(limit)
    fun observeRecent(limit: Int = DEFAULT_RECENT): Flow<List<IngestionLog>> = dao.observeRecent(limit)
    suspend fun getById(id: Long): IngestionLog? = dao.getById(id)
    suspend fun count(): Int = dao.count()
    suspend fun pruneToMostRecent(keep: Int = DEFAULT_KEEP) = dao.pruneToMostRecent(keep)

    companion object {
        const val DEFAULT_RECENT = 200
        const val DEFAULT_KEEP = 500
    }
}
