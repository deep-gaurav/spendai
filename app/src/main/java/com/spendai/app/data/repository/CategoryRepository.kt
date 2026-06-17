package com.spendai.app.data.repository

import com.spendai.app.data.local.dao.CategoryDao
import com.spendai.app.data.local.entity.Category
import kotlinx.coroutines.flow.Flow

/**
 * Repository for [Category] rows. The Agent 2 resolver calls
 * [getOrCreate] once per transaction; the Sources screen reads
 * [observeAll] and writes back via [updateEmoji].
 *
 * ## Dedup rule
 *
 * Lookup is by `name.trim().lowercase()` (the same normalisation
 * the entity uses for its unique index). The first time a name
 * is seen, a new [Category] row is created with the LLM's emoji
 * (or the default if the LLM omitted it). Subsequent uses of
 * the same name preserve the original emoji — the LLM is usually
 * consistent, so the "first wins" rule keeps the visual identity
 * stable even if the LLM's tone shifts between runs.
 */
class CategoryRepository(private val dao: CategoryDao) {

    suspend fun getOrCreate(name: String, emoji: String?, now: Long): Category {
        val trimmedName = name.trim()
        val normalized = normalize(trimmedName)
        if (normalized.isEmpty()) {
            // Caller should not be calling us with a blank name,
            // but defensively return a fresh "Other" bucket so the
            // UI never loses its category badge.
            return insertNew(trimmedName.ifEmpty { "Other" }, emoji, now)
        }
        val existing = dao.findByNormalizedName(normalized)
        if (existing != null) return existing
        return insertNew(trimmedName, emoji, now)
    }

    private suspend fun insertNew(name: String, emoji: String?, now: Long): Category {
        val row = Category(
            name = name,
            normalizedName = normalize(name),
            emoji = emoji?.takeIf { it.isNotBlank() } ?: Category.DEFAULT_EMOJI,
            createdAt = now,
        )
        val id = dao.insertIgnore(row)
        if (id > 0L) return row.copy(id = id)
        // Lost a race with another concurrent insert. Read the
        // committed row back so the caller still gets a stable id.
        return dao.findByNormalizedName(row.normalizedName)
            ?: error("Category insert raced and the row could not be re-read")
    }

    suspend fun getById(id: Long): Category? = dao.getById(id)

    suspend fun getAllOnce(): List<Category> = dao.getAllOnce()

    fun observeAll(): Flow<List<Category>> = dao.observeAll()

    suspend fun updateEmoji(id: Long, emoji: String) = dao.updateEmoji(id, emoji)

    private fun normalize(raw: String): String = raw.trim().lowercase()

    companion object {
        suspend fun nameFor(repo: CategoryRepository, id: Long?): String? {
            if (id == null) return null
            return repo.getById(id)?.name
        }
    }
}
