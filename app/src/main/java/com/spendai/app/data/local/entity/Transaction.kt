package com.spendai.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single financial event the user has been informed of.
 *
 * The canonical row the home screen aggregates. Always carries an
 * [Account] (which [FinancialSource] the event was reported by), and
 * usually a [Merchant] — null only for un-attributed P2P transfers where
 * the receiver is not a recognisable merchant.
 *
 * Amounts are stored as positive paise (Long) so we never have to
 * reason about float drift. The [direction] column carries the sign
 * semantically (`DEBIT` = money out, `CREDIT` = money in).
 *
 * `status = NEEDS_REVIEW` means A3 put this row in the
 * [PendingReview] queue rather than trusting the LLM. `REVERTED` is
 * reserved for a future "I want to mark this as a refund reversal"
 * flow — Phase 2 will surface a button on the home screen.
 */
@Entity(
    tableName = "spend_transaction",
    indices = [
        Index("txnAtMillis"),
        Index(value = ["accountId", "txnAtMillis"]),
        Index("merchantId"),
        Index("rawSmsId"),
        Index("parsedSmsId"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = Merchant::class,
            parentColumns = ["id"],
            childColumns = ["merchantId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = RawSmsMessage::class,
            parentColumns = ["id"],
            childColumns = ["rawSmsId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ParsedSms::class,
            parentColumns = ["id"],
            childColumns = ["parsedSmsId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ]
)
data class Transaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "accountId")
    val accountId: Long,

    @ColumnInfo(name = "merchantId")
    val merchantId: Long? = null,

    @ColumnInfo(name = "rawSmsId")
    val rawSmsId: Long,

    @ColumnInfo(name = "parsedSmsId")
    val parsedSmsId: Long,

    @ColumnInfo(name = "amountPaise")
    val amountPaise: Long,

    @ColumnInfo(name = "currency")
    val currency: String = "INR",

    @ColumnInfo(name = "direction")
    val direction: String = TransactionDirection.DEBIT.name,

    @ColumnInfo(name = "txnAtMillis")
    val txnAtMillis: Long,

    @ColumnInfo(name = "channel")
    val channel: String? = null,

    @ColumnInfo(name = "referenceNo")
    val referenceNo: String? = null,

    @ColumnInfo(name = "status", defaultValue = "'CONFIRMED'")
    val status: String = TransactionStatus.CONFIRMED.name,

    @ColumnInfo(name = "confidence")
    val confidence: Float = 1f,

    @ColumnInfo(name = "notes")
    val notes: String? = null,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
)

enum class TransactionDirection { DEBIT, CREDIT }

enum class TransactionStatus { CONFIRMED, NEEDS_REVIEW, REVERTED }
