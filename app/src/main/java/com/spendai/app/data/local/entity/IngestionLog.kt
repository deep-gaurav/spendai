package com.spendai.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per processed SMS — the audit trail for the per-message
 * A1 → A2 pipeline run.
 *
 * Captures the prompts and raw model responses so the user can
 * inspect why A2 dropped a row A1 said was a transaction (the
 * common "A1 said 5, A2 kept 1" debugging case). Every field
 * marked nullable is intentionally optional: A1 may have been a
 * cache hit (no prompt/response this run), A2 may never have
 * been invoked (A1 said IGNORE), and either step may have
 * thrown.
 *
 * Retention is "keep last N rows" via the [com.spendai.app.data.repository.IngestionLogRepository]
 * (default 500). Old rows are pruned at the end of each run.
 */
@Entity(
    tableName = "ingestion_log",
    indices = [
        Index("rawSmsId"),
        Index("parsedSmsId"),
        Index("transactionId"),
        Index("ingestedAt"),
        Index("a2Outcome"),
    ],
    foreignKeys = [
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
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = Transaction::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class IngestionLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "rawSmsId")
    val rawSmsId: Long,

    @ColumnInfo(name = "parsedSmsId")
    val parsedSmsId: Long? = null,

    @ColumnInfo(name = "transactionId")
    val transactionId: Long? = null,

    @ColumnInfo(name = "ingestedAt")
    val ingestedAt: Long,

    @ColumnInfo(name = "a1Outcome")
    val a1Outcome: String,

    @ColumnInfo(name = "a1Confidence")
    val a1Confidence: Float? = null,

    @ColumnInfo(name = "a1Prompt")
    val a1Prompt: String? = null,

    @ColumnInfo(name = "a1Response")
    val a1Response: String? = null,

    @ColumnInfo(name = "a1Error")
    val a1Error: String? = null,

    @ColumnInfo(name = "a2Outcome")
    val a2Outcome: String? = null,

    @ColumnInfo(name = "a2Confidence")
    val a2Confidence: Float? = null,

    @ColumnInfo(name = "a2Prompt")
    val a2Prompt: String? = null,

    @ColumnInfo(name = "a2Response")
    val a2Response: String? = null,

    @ColumnInfo(name = "a2Error")
    val a2Error: String? = null,

    /**
     * Optional user-typed override prompt for reprompt runs. Set
     * when the user explicitly asked A3 to re-decide with a custom
     * instruction (e.g. "this 50k credit is not a duplicate, link
     * as transfer"). Null on normal pipeline runs.
     */
    @ColumnInfo(name = "userPrompt")
    val userPrompt: String? = null,
)

/** A1 outcomes recorded on the audit row. */
object IngestionLogA1 {
    const val OK = "OK"
    const val IGNORE = "IGNORE"
    const val SKIPPED_A1 = "SKIPPED_A1"
    const val NOT_RUN = "NOT_RUN"
}

/** A2 outcomes recorded on the audit row. */
object IngestionLogA2 {
    const val COMMITTED = "COMMITTED"
    const val DUPLICATE = "DUPLICATE"
    const val SKIPPED_A2 = "SKIPPED_A2"
    const val NOT_RUN = "NOT_RUN"
}
