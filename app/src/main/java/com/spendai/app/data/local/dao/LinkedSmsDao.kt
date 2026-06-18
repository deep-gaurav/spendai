package com.spendai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.spendai.app.data.local.entity.RawSmsMessage

/**
 * Read-only DAO for the Linked SMS view on the transaction detail
 * screen. The screen needs three things for a given
 * com.spendai.app.data.local.entity.Transaction.id:
 *
 *  1. The source raw_sms row for the transaction.
 *  2. Every raw_sms A2 marked as DUPLICATE of this transaction
 *     (i.e. an ingestion_log row with a2Outcome = 'DUPLICATE' and
 *     transactionId = :transactionId).
 *  3. Every raw_sms linked via TransactionLink -- either side of
 *     the edge, with the link type so the UI can show 'Linked
 *     (SELF_TRANSFER)' etc.
 *
 * Each result row carries a LinkedSmsRow.relation discriminator the
 * UI can switch on for the chip colour and label.
 */
@Dao
interface LinkedSmsDao {

    @Query(
        """
        SELECT
            r.id              AS r_id,
            r.senderAddress   AS r_senderAddress,
            r.msgBody         AS r_msgBody,
            r.timestamp       AS r_timestamp,
            'SOURCE'          AS r_relation,
            NULL              AS r_linkType,
            :transactionId    AS r_otherTransactionId
        FROM raw_sms r
        WHERE r.id = :rawSmsId
        """
    )
    suspend fun getSourceSms(rawSmsId: Long, transactionId: Long): LinkedSmsRow?

    @Query(
        """
        SELECT
            r.id              AS r_id,
            r.senderAddress   AS r_senderAddress,
            r.msgBody         AS r_msgBody,
            r.timestamp       AS r_timestamp,
            'DUPLICATE'       AS r_relation,
            NULL              AS r_linkType,
            :transactionId    AS r_otherTransactionId
        FROM raw_sms r
        INNER JOIN ingestion_log l ON l.rawSmsId = r.id
        WHERE l.a2Outcome = 'DUPLICATE' AND l.transactionId = :transactionId
        ORDER BY r.timestamp ASC
        """
    )
    suspend fun getDuplicatesOf(transactionId: Long): List<LinkedSmsRow>

    @Query(
        """
        SELECT
            r.id              AS r_id,
            r.senderAddress   AS r_senderAddress,
            r.msgBody         AS r_msgBody,
            r.timestamp       AS r_timestamp,
            'LINKED'          AS r_relation,
            tl.linkType       AS r_linkType,
            other.id          AS r_otherTransactionId
        FROM raw_sms r
        INNER JOIN spend_transaction t ON t.rawSmsId = r.id
        INNER JOIN transaction_link tl ON
                (tl.fromTransactionId = :transactionId AND tl.toTransactionId = t.id)
             OR (tl.toTransactionId   = :transactionId AND tl.fromTransactionId = t.id)
        INNER JOIN spend_transaction other ON other.id = CASE
                WHEN tl.fromTransactionId = :transactionId THEN tl.toTransactionId
                ELSE tl.fromTransactionId
            END
        WHERE t.id != :transactionId
        ORDER BY r.timestamp ASC
        """
    )
    suspend fun getLinkedSms(transactionId: Long): List<LinkedSmsRow>
}

/**
 * Flat projection of a single linked-SMS view row.
 *
 * relation is one of SOURCE (the transaction's own SMS), DUPLICATE
 * (an SMS A2 marked as a duplicate of this transaction), or LINKED
 * (an SMS tied to this transaction via TransactionLink).
 */
data class LinkedSmsRow(
    @androidx.room.ColumnInfo(name = "r_id") val id: Long,
    @androidx.room.ColumnInfo(name = "r_senderAddress") val senderAddress: String,
    @androidx.room.ColumnInfo(name = "r_msgBody") val msgBody: String,
    @androidx.room.ColumnInfo(name = "r_timestamp") val timestamp: Long,
    @androidx.room.ColumnInfo(name = "r_relation") val relation: String,
    @androidx.room.ColumnInfo(name = "r_linkType") val linkType: String?,
    @androidx.room.ColumnInfo(name = "r_otherTransactionId") val otherTransactionId: Long?,
)
