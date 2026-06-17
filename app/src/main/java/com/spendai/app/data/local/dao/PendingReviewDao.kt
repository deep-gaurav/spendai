package com.spendai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spendai.app.data.local.entity.PendingReview
import com.spendai.app.data.local.entity.PendingReviewKind
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingReviewDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: PendingReview): Long

    @Query("SELECT * FROM pending_review WHERE kind = :kind AND resolvedAt IS NULL ORDER BY createdAt ASC")
    fun observeOpen(kind: String = PendingReviewKind.TRANSACTION.name): Flow<List<PendingReview>>

    @Query("SELECT * FROM pending_review WHERE kind = :kind AND resolvedAt IS NULL ORDER BY createdAt ASC")
    suspend fun getOpenOnce(kind: String = PendingReviewKind.TRANSACTION.name): List<PendingReview>

    @Query("SELECT * FROM pending_review WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PendingReview?

    @Query("UPDATE pending_review SET resolvedAt = :resolvedAt, resolution = :resolution WHERE id = :id")
    suspend fun resolve(id: Long, resolvedAt: Long, resolution: String)
}
