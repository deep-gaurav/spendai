package com.spendai.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-typed instruction that overrides A3's default decision for a
 * specific SMS (and any SMS the user grouped it with).
 *
 * The most recent
 * [com.spendai.app.data.repository.ManualCorrectionRepository.MAX_INJECTED]
 * rows are injected into the A3 system prompt on every subsequent
 * run, so the model stops making the same mistake the user had to
 * correct. Corrections are kept verbatim — no templating, no
 * normalisation — because the whole point is to preserve the user's
 * exact wording for the LLM.
 *
 * `linkedSmsIds` is a JSON array of raw_sms ids the user grouped
 * with the source SMS in the linked-SMS view. Persisted as TEXT
 * (not a Room relationship) because the group is denormalised by
 * design: the user typed it, it is not a real-world invariant we
 * need to enforce.
 *
 * FK on `rawSmsId` is `CASCADE` so deleting a raw_sms row (rare,
 * but allowed) cleans up the corrections that referenced it.
 */
@Entity(
    tableName = "manual_correction",
    indices = [
        Index("rawSmsId"),
        Index("createdAt"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = RawSmsMessage::class,
            parentColumns = ["id"],
            childColumns = ["rawSmsId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ManualCorrection(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "rawSmsId")
    val rawSmsId: Long,

    /**
     * JSON array of raw_sms ids the user grouped with [rawSmsId]
     * in the linked-SMS view (e.g. `"[12, 47, 98]"`). May be empty
     * (a single-SMS correction) but never null.
     */
    @ColumnInfo(name = "linkedSmsIds")
    val linkedSmsIds: String = "[]",

    @ColumnInfo(name = "userPrompt")
    val userPrompt: String,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
)
