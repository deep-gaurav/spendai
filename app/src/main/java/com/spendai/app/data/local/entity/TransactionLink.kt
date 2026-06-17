package com.spendai.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A directed edge between two [Transaction] rows.
 *
 * Why directed and many-to-many? A UPI self-transfer shows up as a
 * debit on the source card AND a credit on the destination wallet —
 * two transactions, one in each direction, with `linkType =
 * SELF_TRANSFER`. Modeling that with a single `related_transaction_id`
 * foreign key can't represent both directions and can't represent
 * multiple edges per row.
 *
 * `linkType`:
 *  - SELF_TRANSFER: the two rows are the two sides of the same
 *    user-initiated transfer (e.g. card → wallet top-up).
 *  - REFUND_OF: this row is a refund of the partner.
 *  - REVERSAL_OF: this row is a chargeback / failure reversal.
 *  - SPLIT_OF: this row is one part of a split payment that the
 *    partner aggregates.
 */
@Entity(
    tableName = "transaction_link",
    indices = [
        Index(value = ["fromTransactionId", "toTransactionId", "linkType"], unique = true),
        Index("fromTransactionId"),
        Index("toTransactionId"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = Transaction::class,
            parentColumns = ["id"],
            childColumns = ["fromTransactionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Transaction::class,
            parentColumns = ["id"],
            childColumns = ["toTransactionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ]
)
data class TransactionLink(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "fromTransactionId")
    val fromTransactionId: Long,

    @ColumnInfo(name = "toTransactionId")
    val toTransactionId: Long,

    @ColumnInfo(name = "linkType")
    val linkType: String,

    @ColumnInfo(name = "confidence")
    val confidence: Float,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
)

enum class TransactionLinkType { SELF_TRANSFER, REFUND_OF, REVERSAL_OF, SPLIT_OF }
