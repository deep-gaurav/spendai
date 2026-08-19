package com.spendai.app.ui.backup

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spendai.app.R
import com.spendai.app.domain.backup.AppRestarter
import com.spendai.app.domain.backup.FullBackupManager
import com.spendai.app.domain.ingestion.IngestionProgress
import com.spendai.app.service.IngestionService
import com.spendai.app.ui.components.BigOutlinedButton
import com.spendai.app.ui.components.BigPrimaryButton
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface BackupUiStatus {
    data object Idle : BackupUiStatus
    data object Exporting : BackupUiStatus
    data object Validating : BackupUiStatus
    data class ConfirmRestore(val tempFile: File) : BackupUiStatus
    data object Restoring : BackupUiStatus
    data class Error(val message: String) : BackupUiStatus
}

/**
 * Whole-database backup & restore. Distinct from the Tracking
 * screen's own export/import (which round-trips only the month
 * history as portable JSON) — this one copies the entire
 * `spendai.db` file via [FullBackupManager], so restoring it
 * replaces every transaction, account, merchant, category, and
 * setting. Restore is destructive and ends in an app restart
 * (see [AppRestarter]); export is safe to run any time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<BackupUiStatus>(BackupUiStatus.Idle) }
    val busy = status is BackupUiStatus.Exporting || status is BackupUiStatus.Validating ||
        status is BackupUiStatus.Restoring

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (isIngestionActive()) {
            status = BackupUiStatus.Error(context.getString(R.string.backup_ingestion_busy))
            return@rememberLauncherForActivityResult
        }
        status = BackupUiStatus.Validating
        scope.launch {
            when (val result = FullBackupManager.validate(context, uri)) {
                is FullBackupManager.ValidationResult.Valid ->
                    status = BackupUiStatus.ConfirmRestore(result.tempFile)
                is FullBackupManager.ValidationResult.Invalid ->
                    status = BackupUiStatus.Error(result.reason)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.backup_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd),
        ) {
            StickerCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                    Text(
                        text = stringResource(R.string.backup_explainer_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.backup_explainer_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            BigPrimaryButton(
                onClick = {
                    if (isIngestionActive()) {
                        status = BackupUiStatus.Error(context.getString(R.string.backup_ingestion_busy))
                        return@BigPrimaryButton
                    }
                    status = BackupUiStatus.Exporting
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { FullBackupManager.export(context) } }
                            .onSuccess { uri ->
                                status = BackupUiStatus.Idle
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/octet-stream"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(intent, context.getString(R.string.backup_export)),
                                )
                            }
                            .onFailure {
                                status = BackupUiStatus.Error(context.getString(R.string.backup_export_error))
                            }
                    }
                },
                text = stringResource(R.string.backup_export),
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )

            BigOutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                text = stringResource(R.string.backup_import),
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )

            when (val current = status) {
                BackupUiStatus.Exporting -> InlineStatus(stringResource(R.string.backup_exporting))
                BackupUiStatus.Validating -> InlineStatus(stringResource(R.string.backup_validating))
                BackupUiStatus.Restoring -> InlineStatus(stringResource(R.string.backup_restoring))
                is BackupUiStatus.Error -> Text(
                    text = current.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }
        }
    }

    val current = status
    if (current is BackupUiStatus.ConfirmRestore) {
        AlertDialog(
            onDismissRequest = {
                current.tempFile.delete()
                status = BackupUiStatus.Idle
            },
            title = { Text(stringResource(R.string.backup_import_confirm_title)) },
            text = { Text(stringResource(R.string.backup_import_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        status = BackupUiStatus.Restoring
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { FullBackupManager.restore(context, current.tempFile) }
                            }.onSuccess {
                                AppRestarter.restart(context)
                            }.onFailure {
                                status = BackupUiStatus.Error(context.getString(R.string.backup_import_error))
                            }
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.backup_import_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        current.tempFile.delete()
                        status = BackupUiStatus.Idle
                    },
                ) {
                    Text(stringResource(R.string.onboarding_cancel))
                }
            },
        )
    }
}

/** Same "is a run in progress" check [com.spendai.app.ui.home.HomeScreen]'s IngestCard uses. */
private fun isIngestionActive(): Boolean {
    val progress = IngestionService.progress.value
    return progress !is IngestionProgress.Idle &&
        progress !is IngestionProgress.Done &&
        progress !is IngestionProgress.Failure &&
        progress !is IngestionProgress.Cancelled
}

@Composable
private fun InlineStatus(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
