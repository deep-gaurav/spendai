package com.spendai.app.data.repository

import com.spendai.app.data.local.dao.MonthlySnapshotDao
import com.spendai.app.data.local.entity.MonthlySnapshot
import kotlinx.coroutines.flow.Flow

class MonthlySnapshotRepository(private val dao: MonthlySnapshotDao) {
    fun observeAll(): Flow<List<MonthlySnapshot>> = dao.observeAll()
    suspend fun getAllOnce(): List<MonthlySnapshot> = dao.getAllOnce()
    suspend fun upsertAll(rows: List<MonthlySnapshot>) = dao.upsertAll(rows)
}
