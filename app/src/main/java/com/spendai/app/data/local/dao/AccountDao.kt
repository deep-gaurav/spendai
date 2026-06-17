package com.spendai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spendai.app.data.local.entity.Account
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: Account): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: Account): Long

    @Query("SELECT * FROM account WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Account?

    @Query("SELECT * FROM account WHERE sourceId = :sourceId ORDER BY id ASC")
    suspend fun getBySource(sourceId: Long): List<Account>

    @Query("SELECT * FROM account ORDER BY id ASC")
    suspend fun getAllOnce(): List<Account>

    @Query("SELECT * FROM account ORDER BY id ASC")
    fun observeAll(): Flow<List<Account>>

    @Query("SELECT * FROM account WHERE sourceId = :sourceId AND maskedNumber = :maskedNumber LIMIT 1")
    suspend fun findBySourceAndMasked(sourceId: Long, maskedNumber: String): Account?
}
