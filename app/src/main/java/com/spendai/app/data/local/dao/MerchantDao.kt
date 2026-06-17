package com.spendai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spendai.app.data.local.entity.Merchant
import kotlinx.coroutines.flow.Flow

@Dao
interface MerchantDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: Merchant): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: Merchant): Long

    @Query("SELECT * FROM merchant WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Merchant?

    @Query("SELECT * FROM merchant WHERE normalizedName = :name LIMIT 1")
    suspend fun findByNormalizedName(name: String): Merchant?

    @Query("SELECT * FROM merchant WHERE vpa = :vpa LIMIT 1")
    suspend fun findByVpa(vpa: String): Merchant?

    @Query("SELECT * FROM merchant ORDER BY firstSeenAt DESC")
    suspend fun getAllOnce(): List<Merchant>

    @Query("SELECT * FROM merchant ORDER BY firstSeenAt DESC")
    fun observeAll(): Flow<List<Merchant>>

    /**
     * Most-recently-seen merchants, capped to [limit] rows.
     *
     * A2's prompt bundle ships a small slice of the merchant list
     * (default 100) to keep the input well under the 64K context
     * budget. New senders land in this window first because the
     * pipeline commits them right after they are first seen, and
     * the resolver re-reads the bundle on every per-message call.
     */
    @Query("SELECT * FROM merchant ORDER BY firstSeenAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<Merchant>
}
