package com.spendai.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Freeform user-supplied context attached to a [Merchant]. The
 * merchant table only carries a tight `isSelf` boolean for the
 * "this is me" flag; everything else the user wants the model to
 * remember lives here as a small key-value table.
 *
 * Examples:
 *  - `(merchantId = 1, kind = NOTE, value = "pani puri vendor")`
 *  - `(merchantId = 1, kind = CATEGORY_HINT, value = "Food")`
 *  - `(merchantId = 1, kind = LABEL, value = "regular-vendor")`
 *
 * A2 reads these rows when it materialises the merchant into the
 * prompt bundle, so a `CATEGORY_HINT` becomes the merchant's
 * category automatically on the next SMS. A3 also sees the same
 * rows, so a saved `NOTE` survives across reprompts.
 *
 * The unique index on `(merchantId, kind)` is the dedup key. Two
 * `NOTE` rows for the same merchant would be ambiguous, so the
 * mutator upserts on conflict. `CATEGORY_HINT` and `LABEL` are
 * also single-valued per merchant; a re-save replaces the old
 * row.
 *
 * FK on `merchantId` is `ON DELETE CASCADE` so deleting a
 * merchant (rare, but allowed) cleans up its metadata.
 */
@Entity(
    tableName = "merchant_metadata",
    indices = [
        Index(value = ["merchantId", "kind"], unique = true),
        Index("merchantId"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = Merchant::class,
            parentColumns = ["id"],
            childColumns = ["merchantId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MerchantMetadata(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "merchantId")
    val merchantId: Long,

    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "value")
    val value: String,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
)

/**
 * The closed set of metadata kinds. New kinds can be added by
 * extending the enum; the [com.spendai.app.domain.agent.insights.MerchantMutator]
 * rejects any kind outside this set so the LLM cannot invent its
 * own categories.
 */
enum class MerchantMetadataKind {
    /** Freeform text the user wants the model to remember. */
    NOTE,

    /**
     * A category name the user wants this merchant to fall into.
     * A2 looks the name up via the same normaliser it uses for
     * fresh category names, so "Food" matches the existing
     * `food` row.
     */
    CATEGORY_HINT,

    /**
     * A more specific display label for the merchant, used by
     * the A2 title builder when the raw merchant name is
     * cryptic (e.g. an account number-looking string).
     */
    LABEL,
}
