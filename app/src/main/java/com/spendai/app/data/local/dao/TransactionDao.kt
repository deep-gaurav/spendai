package com.spendai.app.data.local.dao

import androidx.room.ColumnInfo
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

    @Query("SELECT * FROM spend_transaction WHERE txnAtMillis >= :startMillis AND txnAtMillis <= :endMillis ORDER BY abs(txnAtMillis - :targetMillis) ASC, id DESC")
    suspend fun getTransactionsInRange(startMillis: Long, endMillis: Long, targetMillis: Long): List<Transaction>

    @Query("UPDATE spend_transaction SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    /**
     * Hot stream of every transaction with its denormalised display
     * fields (merchant name, account short label, category emoji +
     * name). The home and transactions screens consume this so the
     * row can render title/emoji/time/account in a single
     * subscription without a separate JOIN in Kotlin.
     *
     * `LEFT JOIN` covers P2P transactions without a merchant and
     * uncategorised transactions without a category.
     */
    @Query(
        """
        SELECT
            t.id AS t_id,
            t.accountId AS t_accountId,
            t.merchantId AS t_merchantId,
            t.rawSmsId AS t_rawSmsId,
            t.parsedSmsId AS t_parsedSmsId,
            t.amountPaise AS t_amountPaise,
            t.currency AS t_currency,
            t.direction AS t_direction,
            t.txnAtMillis AS t_txnAtMillis,
            t.channel AS t_channel,
            t.referenceNo AS t_referenceNo,
            t.status AS t_status,
            t.confidence AS t_confidence,
            t.notes AS t_notes,
            t.title AS t_title,
            t.categoryId AS t_categoryId,
            t.createdAt AS t_createdAt,
            m.name AS m_name,
            a.issuer AS a_issuer,
            a.maskedNumber AS a_maskedNumber,
            a.colorHex AS a_colorHex,
            c.emoji AS c_emoji,
            c.name AS c_name
        FROM spend_transaction t
        LEFT JOIN merchant m ON t.merchantId = m.id
        LEFT JOIN account a  ON t.accountId  = a.id
        LEFT JOIN category c ON t.categoryId = c.id
        ORDER BY t.txnAtMillis DESC
        """
    )
    fun observeAllWithDetails(): Flow<List<TransactionDetailsRow>>
}

/**
 * Flat projection of a transaction joined with its display fields.
 *
 * All `t_*` columns mirror [Transaction]. The other columns are
 * nullable because the join is `LEFT JOIN` — a transaction may
 * have no merchant (P2P), no account (shouldn't happen — FK is
 * NOT NULL), or no category (yet).
 */
data class TransactionDetailsRow(
    @ColumnInfo(name = "t_id") val id: Long,
    @ColumnInfo(name = "t_accountId") val accountId: Long,
    @ColumnInfo(name = "t_merchantId") val merchantId: Long?,
    @ColumnInfo(name = "t_rawSmsId") val rawSmsId: Long,
    @ColumnInfo(name = "t_parsedSmsId") val parsedSmsId: Long,
    @ColumnInfo(name = "t_amountPaise") val amountPaise: Long,
    @ColumnInfo(name = "t_currency") val currency: String,
    @ColumnInfo(name = "t_direction") val direction: String,
    @ColumnInfo(name = "t_txnAtMillis") val txnAtMillis: Long,
    @ColumnInfo(name = "t_channel") val channel: String?,
    @ColumnInfo(name = "t_referenceNo") val referenceNo: String?,
    @ColumnInfo(name = "t_status") val status: String,
    @ColumnInfo(name = "t_confidence") val confidence: Float,
    @ColumnInfo(name = "t_notes") val notes: String?,
    @ColumnInfo(name = "t_title") val title: String?,
    @ColumnInfo(name = "t_categoryId") val categoryId: Long?,
    @ColumnInfo(name = "t_createdAt") val createdAt: Long,
    @ColumnInfo(name = "m_name") val merchantName: String?,
    @ColumnInfo(name = "a_issuer") val accountIssuer: String?,
    @ColumnInfo(name = "a_maskedNumber") val accountMaskedNumber: String?,
    @ColumnInfo(name = "a_colorHex") val accountColorHex: String?,
    @ColumnInfo(name = "c_emoji") val categoryEmoji: String?,
    @ColumnInfo(name = "c_name") val categoryName: String?,
) {
    fun toTransaction(): Transaction = Transaction(
        id = id,
        accountId = accountId,
        merchantId = merchantId,
        rawSmsId = rawSmsId,
        parsedSmsId = parsedSmsId,
        amountPaise = amountPaise,
        currency = currency,
        direction = direction,
        txnAtMillis = txnAtMillis,
        channel = channel,
        referenceNo = referenceNo,
        status = status,
        confidence = confidence,
        notes = notes,
        title = title,
        categoryId = categoryId,
        createdAt = createdAt,
    )
}
