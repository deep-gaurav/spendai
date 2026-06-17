package com.spendai.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A raw SMS message as captured by [com.spendai.app.receiver.SmsReceiver].
 *
 * We persist the *entire* original body — no parsing happens at capture time.
 * Downstream code (the worker + LLM) is responsible for turning it into an
 * expense record. Keeping the raw blob around is what makes the FOSS, on-device
 * pipeline debuggable: the user can inspect exactly what the model saw.
 *
 * Indexes:
 *  - `status` for the worker's "give me all UNPARSED" query.
 *  - `(senderAddress, timestamp)` is UNIQUE to dedupe the rare but real case
 *    where dual-SIM ROMs deliver the same SMS twice. If a sender legitimately
 *    sends two messages in the same millisecond, the second is dropped — an
 *    acceptable v1 trade-off and far better than double-counting expenses.
 *  - `parsedSmsId` for the per-row audit lookup from the home screen.
 */
@Entity(
    tableName = "raw_sms",
    indices = [
        Index("status"),
        Index(value = ["senderAddress", "timestamp"], unique = true),
        Index("parsedSmsId"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ParsedSms::class,
            parentColumns = ["id"],
            childColumns = ["parsedSmsId"],
            onDelete = ForeignKey.SET_NULL,
        )
    ]
)
data class RawSmsMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "senderAddress")
    val senderAddress: String,

    @ColumnInfo(name = "msgBody")
    val msgBody: String,

    /** Epoch milliseconds when the message was received. */
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "status")
    val status: SmsStatus = SmsStatus.UNPARSED,

    @ColumnInfo(name = "parsedSmsId")
    val parsedSmsId: Long? = null,
)
