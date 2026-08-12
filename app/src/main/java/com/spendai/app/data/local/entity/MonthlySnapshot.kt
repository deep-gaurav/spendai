package com.spendai.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A locally-persisted aggregate for one calendar month, one
 * currency. Written by the Tracking screen every time it
 * recomputes month totals from `spend_transaction`, so the
 * month-wise history survives even if the underlying transactions
 * are later edited, re-ingested, or deleted (e.g. a raw SMS is
 * cleared and its cascade removes the row it produced). This is
 * the local backup for tracking history; [com.spendai.app.domain.backup.TrackingBackupManager]
 * exports/imports this table to a JSON file for a portable copy.
 *
 * The unique index on ([yearMonth], [currency]) is the upsert key
 * — a re-run for the same month/currency replaces the row rather
 * than accumulating duplicates.
 */
@Entity(
    tableName = "monthly_snapshot",
    indices = [Index(value = ["yearMonth", "currency"], unique = true)],
)
data class MonthlySnapshot(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** ISO year-month, e.g. "2026-08" ([java.time.YearMonth.toString]). */
    @ColumnInfo(name = "yearMonth")
    val yearMonth: String,

    @ColumnInfo(name = "currency")
    val currency: String,

    @ColumnInfo(name = "totalDebitPaise")
    val totalDebitPaise: Long,

    @ColumnInfo(name = "totalCreditPaise")
    val totalCreditPaise: Long,

    @ColumnInfo(name = "txnCount")
    val txnCount: Int,

    @ColumnInfo(name = "firstTxnAtMillis")
    val firstTxnAtMillis: Long?,

    @ColumnInfo(name = "lastTxnAtMillis")
    val lastTxnAtMillis: Long?,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,
)
