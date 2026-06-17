package com.spendai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spendai.app.data.local.entity.TransactionLink

@Dao
interface TransactionLinkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: TransactionLink): Long

    @Query("SELECT * FROM transaction_link WHERE fromTransactionId = :fromId OR toTransactionId = :toId")
    suspend fun getEdges(fromId: Long, toId: Long): List<TransactionLink>

    @Query("SELECT * FROM transaction_link")
    suspend fun getAllOnce(): List<TransactionLink>
}
