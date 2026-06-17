package com.spendai.app.data.repository

import com.spendai.app.data.local.dao.PendingReviewDao
import com.spendai.app.data.local.entity.PendingReview
import com.spendai.app.data.local.entity.PendingReviewKind
import com.spendai.app.data.local.entity.PendingReviewResolution
import kotlinx.coroutines.flow.Flow

class PendingReviewRepository(private val dao: PendingReviewDao) {
    suspend fun insert(row: PendingReview): Long = dao.insert(row)
    fun observeOpen(kind: String = PendingReviewKind.TRANSACTION.name): Flow<List<PendingReview>> =
        dao.observeOpen(kind)
    suspend fun getOpenOnce(kind: String = PendingReviewKind.TRANSACTION.name): List<PendingReview> =
        dao.getOpenOnce(kind)
    suspend fun getById(id: Long): PendingReview? = dao.getById(id)
    suspend fun resolve(id: Long, resolution: PendingReviewResolution, now: Long) {
        dao.resolve(id, now, resolution.name)
    }
}
