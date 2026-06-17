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
 *  - `(status, processedAt, timestamp)` for the service's "give me pending
 *    in range" query. The new `processedAt` column lets the pipeline mark
 *    a row as terminally done (committed OR ignored) in one indexed
 *    lookup, instead of joining `spend_transaction` to filter committed
 *    rows.
 *
 * ## Idempotency (v6)
 *
 * The previous design asked "is this row committed?" via
 * `raw_sms.id NOT IN (SELECT rawSmsId FROM spend_transaction)`. That
 * missed IGNORE rows (which are terminal but have no transaction) and
 * forced an extra join. v6 replaces it with two nullable columns:
 *
 *  - [processedAt]: set to `System.currentTimeMillis()` when the row
 *    reaches a terminal state (TRANSACTION committed, A1 said IGNORE,
 *    or content provider has no row for it). `null` means "still
 *    pending — a future run may pick this up".
 *  - [lastError]: set to the A1/A2 error string when the pipeline
 *    skipped the row. Cleared on the next successful commit. Surfaced
 *    on the debug log so the user can see why a message is stuck.
 *
 * The pending query is now
 * `WHERE status = UNPARSED AND processedAt IS NULL`, which is a
 * single indexed scan.
 */
@Entity(
    tableName = "raw_sms",
    indices = [
        Index("status"),
        Index(value = ["senderAddress", "timestamp"], unique = true),
        Index("parsedSmsId"),
        Index(value = ["status", "processedAt", "timestamp"]),
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

    /**
     * Epoch millis when the row reached a terminal state. `null`
     * means "still pending — the service may pick this up on the
     * next run". A row that fails A1 or A2 leaves this null and
     * writes [lastError] instead.
     */
    @ColumnInfo(name = "processedAt")
    val processedAt: Long? = null,

    /**
     * Last error string from a skipped A1/A2 attempt. Cleared on
     * the next successful commit. Surfaced on the debug log.
     */
    @ColumnInfo(name = "lastError")
    val lastError: String? = null,
)
