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
}
