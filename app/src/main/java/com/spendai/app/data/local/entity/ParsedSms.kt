package com.spendai.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Agent 1's structured output for a single [RawSmsMessage].
 *
 * Persisted for audit and replay. The home screen can show "model saw
 * this; here's what it thought" so the user trusts (or corrects) the
 * pipeline. `a1RawJson` is the model's literal output, kept verbatim so
 * we can re-parse it if the schema evolves.
 *
 * `kind = IGNORE` means A1 decided the SMS is not a financial event
 * (OTP, marketing, partial system alert, ...). All nullable fields are
 * null in that case.
 */
@Entity(
    tableName = "parsed_sms",
    indices = [Index(value = ["rawSmsId"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = RawSmsMessage::class,
            parentColumns = ["id"],
            childColumns = ["rawSmsId"],
            onDelete = ForeignKey.CASCADE,
        )
    ]
)
data class ParsedSms(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "rawSmsId")
    val rawSmsId: Long,

    @ColumnInfo(name = "parsedAt")
    val parsedAt: Long,

    @ColumnInfo(name = "kind")
    val kind: String = ParsedSmsKind.TRANSACTION.name,

    @ColumnInfo(name = "amountPaise")
    val amountPaise: Long? = null,

    @ColumnInfo(name = "currency")
    val currency: String? = null,

    @ColumnInfo(name = "direction")
    val direction: String? = null,

    @ColumnInfo(name = "txnAtMillis")
    val txnAtMillis: Long? = null,

    @ColumnInfo(name = "channel")
    val channel: String? = null,

    @ColumnInfo(name = "sourceKeyHint")
    val sourceKeyHint: String? = null,

    @ColumnInfo(name = "merchantRaw")
    val merchantRaw: String? = null,

    @ColumnInfo(name = "cardLast4Hint")
    val cardLast4Hint: String? = null,

    @ColumnInfo(name = "accountLast4Hint")
    val accountLast4Hint: String? = null,

    @ColumnInfo(name = "referenceNo")
    val referenceNo: String? = null,

    @ColumnInfo(name = "a1Confidence")
    val a1Confidence: Float = 0f,

    @ColumnInfo(name = "a1RawJson")
    val a1RawJson: String = "{}",
)

enum class ParsedSmsKind { TRANSACTION, IGNORE }
