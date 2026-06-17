package com.spendai.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per item the home screen needs to surface as a "review me"
 * card. Polymorphic over [PendingReviewKind]:
 *
 *  - TRANSACTION: `targetId` is a [Transaction.id] the model is
 *    unsure about (low confidence, ambiguous merchant, suspicious
 *    direction, ...). The home card shows the suggested values from
 *    `suggestedJson` for the user to accept / edit / reject.
 *  - SOURCE: `targetId` is a [FinancialSource.id] the agent
 *    discovered for the first time. The home card asks the user to
 *    label it (bank name, instrument type) and the worker will move
 *    the source to `CONFIRMED` once they do.
 *
 * `suggestedJson` is a serialised blob (agent output) the home screen
 * pre-fills into the edit form. The exact shape depends on `kind`.
 *
 * The `(kind, resolvedAt)` index keeps the "open queue" query fast.
 */
@Entity(
    tableName = "pending_review",
    indices = [Index(value = ["kind", "resolvedAt"])]
)
data class PendingReview(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "kind")
    val kind: String = PendingReviewKind.TRANSACTION.name,

    @ColumnInfo(name = "targetId")
    val targetId: Long,

    @ColumnInfo(name = "promptSummary")
    val promptSummary: String,

    @ColumnInfo(name = "suggestedJson")
    val suggestedJson: String,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    @ColumnInfo(name = "resolvedAt")
    val resolvedAt: Long? = null,

    @ColumnInfo(name = "resolution")
    val resolution: String? = null,
)

enum class PendingReviewKind { TRANSACTION, SOURCE }
enum class PendingReviewResolution { ACCEPTED, EDITED, REJECTED }
