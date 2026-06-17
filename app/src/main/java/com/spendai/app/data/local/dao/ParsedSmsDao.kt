package com.spendai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spendai.app.data.local.entity.ParsedSms
import kotlinx.coroutines.flow.Flow

@Dao
interface ParsedSmsDao {
    /**
     * Insert a parsed_sms row. The `rawSmsId` column has a UNIQUE
     * index — if a row for the same `rawSmsId` already exists, the
     * insert returns `-1` and the existing row is left untouched.
     *
     * The IGNORE strategy (replacing the previous ABORT) means a
     * race between two ingestion runs (e.g. the worker and the
     * service firing concurrently) can no longer crash a run —
     * the second inserter sees the existing row and reuses it
     * via [getByRawSms]. Defense in depth on top of the
     * pipeline's cache check.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ParsedSms): Long

    @Query("SELECT * FROM parsed_sms WHERE rawSmsId = :rawSmsId LIMIT 1")
    suspend fun getByRawSms(rawSmsId: Long): ParsedSms?

    @Query("SELECT * FROM parsed_sms WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ParsedSms?

    @Query("SELECT * FROM parsed_sms ORDER BY parsedAt DESC")
    fun observeAll(): Flow<List<ParsedSms>>

    /**
     * Remove the parsed_sms row for a raw SMS. Used by the pipeline to
     * clear a synthetic-IGNORE placeholder (a1RawJson empty, a1Confidence
     * 0.0) before re-running A1. The foreign key on raw_sms.parsedSmsId
     * is ON DELETE SET NULL, so the raw_sms row stays in place and
     * becomes eligible for re-parsing.
     */
    @Query("DELETE FROM parsed_sms WHERE rawSmsId = :rawSmsId")
    suspend fun deleteByRawSms(rawSmsId: Long)
}
