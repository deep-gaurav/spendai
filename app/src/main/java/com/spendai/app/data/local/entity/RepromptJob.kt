package com.spendai.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An in-flight or completed A3 reprompt job.
 *
 * The reprompt flow runs on the foreground [com.spendai.app.service.IngestionService]
 * (action `ACTION_REPROMPT`). This table is the durable execution
 * record of every job the service is asked to perform:
 *
 *  - A row is inserted with [RepromptJobStatus.RUNNING] the moment
 *    the service starts a reprompt, so process death does not lose
 *    the prompt.
 *  - The cold-start scan in [com.spendai.app.service.IngestionService]
 *    picks up rows still in [RepromptJobStatus.PENDING] or
 *    [RepromptJobStatus.RUNNING] whose `lastAttemptAt` is older
 *    than 10 minutes and re-runs them. This is the "if the OS
 *    killed the service" safety net.
 *  - On terminal completion the row is flipped to
 *    [RepromptJobStatus.COMPLETED] (with `completedAt`) or
 *    [RepromptJobStatus.FAILED] (with `errorMessage`).
 *
 * The companion [com.spendai.app.data.local.entity.ManualCorrection]
 * table still holds the *lesson* the user typed; this table holds
 * the *execution* of that lesson. They are kept separate on
 * purpose so an empty `manual_correction` row can survive even if
 * the corresponding job was cancelled before A3 ran.
 *
 * `rawSmsIds` is a JSON array (`"[12, 47, 98]"`), denormalised by
 * design: the user grouped the SMSes in the linked-SMS view, that
 * grouping is not a real-world invariant we need to enforce.
 *
 * FK on `transactionId` is `ON DELETE SET NULL` so deleting the
 * transaction from the edit screen does not cascade-kill the job
 * record (the user can still re-issue a reprompt from the linked
 * SMS view).
 */
@Entity(
    tableName = "reprompt_job",
    indices = [
        Index("status"),
        Index("createdAt"),
        Index("transactionId"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = Transaction::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class RepromptJob(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /**
     * JSON array of raw_sms ids the user grouped together in the
     * linked-SMS view. Always non-null; may be a single-element
     * array for a single-SMS reprompt.
     */
    @ColumnInfo(name = "rawSmsIds")
    val rawSmsIds: String,

    @ColumnInfo(name = "userPrompt")
    val userPrompt: String,

    /**
     * Optional FK to the [Transaction] the user opened. Used by the
     * edit screen to detect "is there a reprompt running for this
     * transaction?" without joining on the JSON blob. Null for
     * free-standing reprompts.
     */
    @ColumnInfo(name = "transactionId")
    val transactionId: Long? = null,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    @ColumnInfo(name = "status")
    val status: String = RepromptJobStatus.PENDING.name,

    @ColumnInfo(name = "errorMessage")
    val errorMessage: String? = null,

    @ColumnInfo(name = "attemptCount")
    val attemptCount: Int = 0,

    @ColumnInfo(name = "lastAttemptAt")
    val lastAttemptAt: Long? = null,

    @ColumnInfo(name = "completedAt")
    val completedAt: Long? = null,
)

/** Lifecycle of a [RepromptJob]. */
enum class RepromptJobStatus {
    /** Inserted by the UI, not yet picked up by the service. */
    PENDING,

    /** The service is currently running this job. */
    RUNNING,

    /** The service finished and A3 committed at least one transaction. */
    COMPLETED,

    /** The service gave up. [RepromptJob.errorMessage] has the reason. */
    FAILED,
}
