package com.spendai.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A counterparty the user has transacted with — a merchant, a person on
 * UPI, an employer, etc.
 *
 * `normalizedName` is the dedup key. It is computed by
 * [com.spendai.app.domain.model.MerchantNormalizer] (pure Kotlin, no LLM).
 * The original `name` is preserved for display.
 *
 * `vpa` is the UPI handle (e.g. `zomato@okhdfcbank`) when known. We
 * index it because UPI-to-UPI transfers are the most common linking
 * case and we want fast lookups.
 *
 * `categoryId` is a soft FK to [Category]. It is nullable because
 * some merchants (e.g. one-off P2P transfers) are not categorised.
 */
@Entity(
    tableName = "merchant",
    indices = [
        Index(value = ["normalizedName"], unique = true),
        Index("vpa"),
        Index("categoryId"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ]
)
data class Merchant(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "normalizedName")
    val normalizedName: String,

    @ColumnInfo(name = "vpa")
    val vpa: String? = null,

    @ColumnInfo(name = "categoryId")
    val categoryId: Long? = null,

    @ColumnInfo(name = "firstSeenAt")
    val firstSeenAt: Long,
)
