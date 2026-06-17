package com.spendai.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A counterparty we've recognised as a source of financial SMS traffic.
 *
 * `sourceKey` is a stable identifier we derive from the sender address
 * (e.g. "Bank_3001" for SMS short-code 3001 issued by a bank). The composite
 * key is intentionally human-readable so an offline audit of the DB is
 * possible — the whole privacy story relies on the user being able to
 * inspect what's stored.
 *
 * `deducedType` is the LLM's best guess at the source's role
 * (CREDIT_CARD, UPI, NEFT, WALLET, ...). It is intentionally a plain String
 * rather than a Room enum so we can add new types without a migration.
 *
 * `instrumentType` is the structured counterpart to `deducedType` and is
 * one of [SourceInstrumentType] (stored as a string column). A new source
 * defaults to `UNKNOWN` until the user labels it or A3 promotes it from
 * the review queue.
 *
 * `status` is [SourceStatus]. New sources land in `NEEDS_REVIEW` so the
 * home screen can show a "label this source" card; once the user
 * confirms it flips to `CONFIRMED` and stays there.
 */
@Entity(
    tableName = "financial_source",
    indices = [Index(value = ["sourceKey"], unique = true)]
)
data class FinancialSource(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "sourceKey")
    val sourceKey: String,

    @ColumnInfo(name = "deducedType")
    val deducedType: String,

    /**
     * Human-readable label set by the user (e.g. "HDFC Credit Card"). Null
     * until the user renames it in the UI, or until A3 promotes the source
     * with a confident guess.
     */
    @ColumnInfo(name = "userLabel")
    val userLabel: String? = null,

    @ColumnInfo(name = "firstSeenTimestamp")
    val firstSeenTimestamp: Long,

    @ColumnInfo(name = "displayName")
    val displayName: String? = null,

    @ColumnInfo(name = "bankName")
    val bankName: String? = null,

    @ColumnInfo(name = "accountLast4")
    val accountLast4: String? = null,

    @ColumnInfo(name = "instrumentType")
    val instrumentType: String = SourceInstrumentType.UNKNOWN.name,

    @ColumnInfo(name = "status", defaultValue = "'NEEDS_REVIEW'")
    val status: String = SourceStatus.NEEDS_REVIEW.name,

    @ColumnInfo(name = "confirmedAt")
    val confirmedAt: Long? = null,
)

/** Coarse role of a [FinancialSource]. Stored as a string column. */
enum class SourceInstrumentType {
    UNKNOWN,
    CARD,
    ACCOUNT,
    WALLET,
    UPI_HANDLE,
}

/** Lifecycle state of a [FinancialSource]. */
enum class SourceStatus {
    /** User has labelled this source and we trust it. */
    CONFIRMED,

    /** Discovered by an agent and queued for the user to label. */
    NEEDS_REVIEW,
}
