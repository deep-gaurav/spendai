package com.spendai.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A spending category, dynamically created by Agent 2 the first
 * time it sees a new label. Each category carries a single emoji
 * that the LLM picks (or the user overrides in the Sources screen).
 *
 * The unique index on [normalizedName] is the dedup key. The
 * `name` field preserves the original casing/spacing for display
 * (e.g. "HDFC Salary", "Food", "Coffee run"); the normalised
 * counterpart is what the resolver uses to decide whether a name
 * has been seen before.
 */
@Entity(
    tableName = "category",
    indices = [Index(value = ["normalizedName"], unique = true)],
)
data class Category(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "normalizedName")
    val normalizedName: String,

    @ColumnInfo(name = "emoji")
    val emoji: String = DEFAULT_EMOJI,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
) {
    companion object {
        const val DEFAULT_EMOJI: String = "\uD83D\uDCB8" // money-with-wings
    }
}
