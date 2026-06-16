package com.spendai.app.ui.download

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.R
import com.spendai.app.ui.setup.SetupViewModel
import java.util.Locale
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    setupViewModel: SetupViewModel,
    onContinue: () -> Unit,
    viewModel: DownloadViewModel = viewModel(factory = DownloadViewModelFactory(setupViewModel)),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Icon(
                Icons.Outlined.CloudDownload,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.download_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.download_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Progress
            when {
                ui.present -> AlreadyPresentRow()
                ui.running -> {
                    val progress = if (ui.totalBytes > 0) {
                        ui.bytesDownloaded.toFloat() / ui.totalBytes.toFloat()
                    } else null
                    Text(
                        stringResource(R.string.download_running),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        formatBytesLabel(ui.bytesDownloaded, ui.totalBytes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ui.error != null -> {
                    Text(
                        ui.error ?: stringResource(R.string.download_error_generic),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> Text(
                    stringResource(R.string.download_idle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Source label
            Text(
                stringResource(R.string.download_source_label).uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.download_source_value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )

            Spacer(Modifier.height(8.dp))

            when {
                ui.present -> {
                    Button(
                        onClick = onContinue,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.onboarding_continue)) }
                }
                ui.running -> {
                    OutlinedButton(
                        onClick = viewModel::cancel,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.onboarding_cancel)) }
                }
                ui.error != null -> {
                    Button(
                        onClick = viewModel::retry,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.onboarding_retry)) }
                }
                else -> {
                    Button(
                        onClick = viewModel::start,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.download_start)) }
                }
            }
        }
    }
}

@Composable
private fun AlreadyPresentRow() {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.download_skip_already_present),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

private fun formatBytesLabel(downloaded: Long, total: Long): String {
    val dl = formatBytes(downloaded)
    return if (total > 0) {
        val tot = formatBytes(total)
        "$dl of $tot"
    } else {
        dl
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var i = 0
    while (value >= 1024.0 && i < units.lastIndex) {
        value /= 1024.0
        i++
    }
    return String.format(Locale.US, "%.1f %s", value, units[i])
}

@Composable
private fun DownloadViewModelFactory(
    setupViewModel: SetupViewModel,
): androidx.lifecycle.ViewModelProvider.Factory =
    androidx.lifecycle.viewmodel.viewModelFactory {
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as android.app.Application)
            DownloadViewModel(app, setupViewModel)
        }
    }
