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
 * (CREDIT_CARD, UPI, NEFT, WALLET, …). It is intentionally a plain String
 * rather than a Room enum so we can add new types without a migration.
 *
 * `userLabel` is set when the user explicitly renames a source in the UI
 * (Phase 2). Null until then.
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

    @ColumnInfo(name = "userLabel")
    val userLabel: String? = null,

    @ColumnInfo(name = "firstSeenTimestamp")
    val firstSeenTimestamp: Long
)
