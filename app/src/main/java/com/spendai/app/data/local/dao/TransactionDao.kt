package com.spendai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.local.entity.TransactionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: Transaction): Long

    @Update
    suspend fun update(row: Transaction)

    @Delete
    suspend fun delete(row: Transaction)

    @Query("SELECT * FROM spend_transaction WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Transaction?

    @Query("SELECT * FROM spend_transaction WHERE parsedSmsId = :parsedSmsId LIMIT 1")
    suspend fun getByParsedSms(parsedSmsId: Long): Transaction?

    @Query("SELECT * FROM spend_transaction ORDER BY txnAtMillis DESC")
    fun observeAll(): Flow<List<Transaction>>

    @Query("SELECT * FROM spend_transaction WHERE txnAtMillis >= :sinceMillis ORDER BY txnAtMillis DESC")
    suspend fun getSince(sinceMillis: Long): List<Transaction>

    @Query("UPDATE spend_transaction SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)
}
