package com.spendai.app.domain.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.spendai.app.data.local.entity.MonthlySnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Portable JSON backup of the Tracking screen's month-wise
 * history ([MonthlySnapshot] rows). This is separate from the
 * write-through Room backup ([com.spendai.app.data.repository.MonthlySnapshotRepository])
 * — that one is automatic and local-only; this one produces a
 * file the user can move off-device (share, cloud drive) or
 * restore from after a reinstall.
 *
 * The export/import surface is deliberately narrow: only the
 * aggregated month totals round-trip, not the full transaction
 * ledger. Restoring individual transactions would require
 * resolving accounts/merchants/categories by foreign key, which
 * is unsafe to reconstruct from a portable file.
 */
object TrackingBackupManager {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /**
     * Serialises [snapshots] to a timestamped file under this
     * app's external-files "backups" directory and returns a
     * `content://` [Uri] (via [FileProvider]) suitable for sharing
     * or opening.
     */
    fun export(context: Context, snapshots: List<MonthlySnapshot>): Uri {
        val payload = BackupFile(
            exportedAt = System.currentTimeMillis(),
            months = snapshots.map { it.toDto() },
        )
        val dir = File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "spendai_tracking_backup_$stamp.json")
        file.writeText(json.encodeToString(payload))
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * Reads and parses a previously exported backup file. Returns
     * the [MonthlySnapshot] rows ready to upsert; the caller owns
     * the DB write.
     */
    fun import(context: Context, uri: Uri): List<MonthlySnapshot> {
        val text = context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: throw IllegalArgumentException("Could not open backup file")
        val payload = json.decodeFromString(BackupFile.serializer(), text)
        return payload.months.map { it.toEntity() }
    }

    @Serializable
    private data class BackupFile(
        val version: Int = 1,
        val exportedAt: Long,
        val months: List<MonthlySnapshotDto>,
    )

    @Serializable
    private data class MonthlySnapshotDto(
        val yearMonth: String,
        val currency: String,
        val totalDebitPaise: Long,
        val totalCreditPaise: Long,
        val txnCount: Int,
        val firstTxnAtMillis: Long?,
        val lastTxnAtMillis: Long?,
        val updatedAt: Long,
    )

    private fun MonthlySnapshot.toDto() = MonthlySnapshotDto(
        yearMonth = yearMonth,
        currency = currency,
        totalDebitPaise = totalDebitPaise,
        totalCreditPaise = totalCreditPaise,
        txnCount = txnCount,
        firstTxnAtMillis = firstTxnAtMillis,
        lastTxnAtMillis = lastTxnAtMillis,
        updatedAt = updatedAt,
    )

    private fun MonthlySnapshotDto.toEntity() = MonthlySnapshot(
        yearMonth = yearMonth,
        currency = currency,
        totalDebitPaise = totalDebitPaise,
        totalCreditPaise = totalCreditPaise,
        txnCount = txnCount,
        firstTxnAtMillis = firstTxnAtMillis,
        lastTxnAtMillis = lastTxnAtMillis,
        updatedAt = updatedAt,
    )
}
