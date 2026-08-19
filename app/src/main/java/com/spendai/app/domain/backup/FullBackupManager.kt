package com.spendai.app.domain.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.core.content.FileProvider
import com.spendai.app.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Whole-database backup and restore. Unlike [TrackingBackupManager]
 * (which round-trips only the `monthly_snapshot` aggregate as
 * portable JSON, deliberately avoiding the transaction graph's
 * foreign keys), this copies the raw `spendai.db` file — the only
 * way to capture every table, including `raw_sms`/`parsed_sms`/
 * `account`/`merchant`/`category` and every foreign key between
 * them, without a risky ID-remapping import.
 *
 * [restore] overwrites the live database out from under Room and
 * does **not** restart the app itself — the caller must do that via
 * [AppRestarter] immediately after, since `SpendAiApp`'s `by lazy`
 * repositories can't be hot-swapped onto a new DB file mid-process.
 */
object FullBackupManager {

    sealed interface ValidationResult {
        data class Valid(val tempFile: File) : ValidationResult
        data class Invalid(val reason: String) : ValidationResult
    }

    /**
     * Checkpoints the WAL into the main file (so the copy is
     * self-contained) and copies `spendai.db` to this app's
     * external-files `backups/` folder, returning a `content://`
     * Uri via the same [FileProvider] authority `TrackingBackupManager`
     * already uses.
     */
    suspend fun export(context: Context): Uri = withContext(Dispatchers.IO) {
        checkpointWal(context)
        val dir = File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dest = File(dir, "spendai_full_backup_$stamp.db")
        AppDatabase.databaseFile(context).copyTo(dest, overwrite = true)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
    }

    /**
     * Copies [uri] into [Context.getCacheDir] and sanity-checks it
     * before anything destructive happens: the SQLite file header,
     * that its `PRAGMA user_version` isn't from a newer app build
     * than this one knows how to migrate, and that it actually
     * contains a `spend_transaction` table. The returned temp file
     * (on [ValidationResult.Valid]) is what [restore] consumes.
     */
    suspend fun validate(context: Context, uri: Uri): ValidationResult = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "restore_candidate.db")
        val input = context.contentResolver.openInputStream(uri)
            ?: return@withContext ValidationResult.Invalid("Could not read the selected file")
        input.use { stream -> temp.outputStream().use { stream.copyTo(it) } }

        val header = ByteArray(SQLITE_MAGIC.size)
        val readBytes = temp.inputStream().use { it.read(header) }
        if (readBytes < SQLITE_MAGIC.size || !header.contentEquals(SQLITE_MAGIC)) {
            temp.delete()
            return@withContext ValidationResult.Invalid("Not a SpendAI backup file")
        }

        return@withContext runCatching {
            SQLiteDatabase.openDatabase(temp.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                if (db.version > CURRENT_SCHEMA_VERSION) {
                    return@withContext ValidationResult.Invalid(
                        "This backup is from a newer version of SpendAI — update the app first",
                    )
                }
                db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='spend_transaction'",
                    null,
                ).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        return@withContext ValidationResult.Invalid("Doesn't look like a SpendAI backup file")
                    }
                }
            }
            ValidationResult.Valid(temp)
        }.getOrElse {
            temp.delete()
            ValidationResult.Invalid("Couldn't read this backup file")
        }
    }

    /**
     * Overwrites the live database with [tempFile] (a temp file
     * previously returned by [validate]). Closes Room's handle
     * first and deletes stale WAL/journal sidecars so the replaced
     * file isn't reopened next to leftover frames from the
     * *previous* database. The caller must restart the process
     * immediately after this returns — nothing in `SpendAiApp` is
     * safe to touch until then.
     */
    suspend fun restore(context: Context, tempFile: File): Unit = withContext(Dispatchers.IO) {
        AppDatabase.closeAndReset()
        val dest = AppDatabase.databaseFile(context)
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            File(dest.path + suffix).delete()
        }
        tempFile.copyTo(dest, overwrite = true)
        tempFile.delete()
    }

    private fun checkpointWal(context: Context) {
        AppDatabase.get(context).openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)")
            .use { it.moveToFirst() }
    }

    /** The 16-byte SQLite file header: ASCII "SQLite format 3" followed by a NUL byte. */
    private val SQLITE_MAGIC: ByteArray = byteArrayOf(
        'S'.code.toByte(), 'Q'.code.toByte(), 'L'.code.toByte(), 'i'.code.toByte(),
        't'.code.toByte(), 'e'.code.toByte(), ' '.code.toByte(), 'f'.code.toByte(),
        'o'.code.toByte(), 'r'.code.toByte(), 'm'.code.toByte(), 'a'.code.toByte(),
        't'.code.toByte(), ' '.code.toByte(), '3'.code.toByte(), 0,
    )

    /** Must track [AppDatabase]'s `@Database(version = ...)`. */
    private const val CURRENT_SCHEMA_VERSION = 10
}
