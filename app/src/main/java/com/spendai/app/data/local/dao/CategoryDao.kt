package com.spendai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spendai.app.data.local.entity.Category
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [Category] rows.
 *
 * Inserts use [OnConflictStrategy.IGNORE] so the resolver can
 * safely re-attempt a name that lost a race with another message
 * and the [findByNormalizedName] lookup will then return the
 * committed row. The resolver pattern is "look up first, insert
 * if missing" so the IGNORE here is just a safety net.
 */
@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: Category): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: Category): Long

    @Query("SELECT * FROM category WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Category?

    @Query("SELECT * FROM category WHERE normalizedName = :name LIMIT 1")
    suspend fun findByNormalizedName(name: String): Category?

    @Query("SELECT * FROM category ORDER BY createdAt DESC")
    suspend fun getAllOnce(): List<Category>

    @Query("SELECT * FROM category ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Category>>

    @Query("UPDATE category SET emoji = :emoji WHERE id = :id")
    suspend fun updateEmoji(id: Long, emoji: String)
}
