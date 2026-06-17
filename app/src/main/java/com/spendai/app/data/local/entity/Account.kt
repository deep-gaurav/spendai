package com.spendai.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A financial instrument the user holds (a card, a bank account, a wallet).
 *
 * One [FinancialSource] can map to many accounts — a single bank sender
 * may SMS about both a credit card and a savings account. The
 * `(sourceId, maskedNumber)` unique index handles that.
 *
 * `maskedNumber` is the human-visible form (e.g. "XXXX1234" or
 * "UPI:user@okhdfcbank"). We never store the full PAN or account number.
 *
 * `colorHex` is the user-assigned accent color (e.g. "#FF6B6B"). Null
 * means "use the default theme color" — the UI layer decides. Stored
 * as text so the palette can be expanded without a migration.
 */
@Entity(
    tableName = "account",
    indices = [
        Index(value = ["sourceId", "maskedNumber"], unique = true),
        Index("sourceId"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = FinancialSource::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        )
    ]
)
data class Account(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "sourceId")
    val sourceId: Long,

    @ColumnInfo(name = "instrumentType")
    val instrumentType: String = SourceInstrumentType.UNKNOWN.name,

    @ColumnInfo(name = "issuer")
    val issuer: String,

    @ColumnInfo(name = "maskedNumber")
    val maskedNumber: String,

    @ColumnInfo(name = "currency")
    val currency: String = "INR",

    @ColumnInfo(name = "holderName")
    val holderName: String? = null,

    @ColumnInfo(name = "colorHex")
    val colorHex: String? = null,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
)
