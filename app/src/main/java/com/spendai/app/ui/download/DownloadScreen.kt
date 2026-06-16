package com.spendai.app.ui.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.R
import com.spendai.app.ui.components.BigOutlinedButton
import com.spendai.app.ui.components.BigPrimaryButton
import com.spendai.app.ui.components.CartoonIcon
import com.spendai.app.ui.components.OnboardingScaffold
import com.spendai.app.ui.components.SectionLabel
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.setup.SetupViewModel
import com.spendai.app.ui.theme.Dimens
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.Locale

@Composable
fun DownloadScreen(
    setupViewModel: SetupViewModel,
    onContinue: () -> Unit,
    viewModel: DownloadViewModel = viewModel(factory = DownloadViewModelFactory(setupViewModel)),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    OnboardingScaffold(
        title = stringResource(R.string.app_name),
        step = 2,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd),
        ) {
            // Hero illustration
            CartoonIcon(
                id = R.drawable.art_download_mascot,
                size = 160.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dimens.SpaceSm),
            )

            Text(
                text = stringResource(R.string.download_title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.download_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Status / progress card
            StickerCard {
                when {
                    ui.present -> AlreadyPresentRow()
                    ui.running -> RunningBlock(
                        bytesDownloaded = ui.bytesDownloaded,
                        totalBytes = ui.totalBytes,
                    )
                    ui.error != null -> Text(
                        text = ui.error ?: stringResource(R.string.download_error_generic),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Text(
                        text = stringResource(R.string.download_idle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Source
            SectionLabel(stringResource(R.string.download_source_label))
            Text(
                text = stringResource(R.string.download_source_value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )

            Spacer(Modifier.height(Dimens.SpaceXs))

            when {
                ui.present -> BigPrimaryButton(
                    onClick = onContinue,
                    text = stringResource(R.string.onboarding_continue),
                    modifier = Modifier.fillMaxWidth(),
                )
                ui.running -> BigOutlinedButton(
                    onClick = viewModel::cancel,
                    text = stringResource(R.string.onboarding_cancel),
                    modifier = Modifier.fillMaxWidth(),
                )
                ui.error != null -> BigPrimaryButton(
                    onClick = viewModel::retry,
                    text = stringResource(R.string.onboarding_retry),
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> BigPrimaryButton(
                    onClick = viewModel::start,
                    text = stringResource(R.string.download_start),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AlreadyPresentRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CartoonIcon(
            id = R.drawable.ic_check_cartoon,
            size = 40.dp,
        )
        Spacer(Modifier.size(Dimens.SpaceSm))
        Text(
            text = stringResource(R.string.download_skip_already_present),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun RunningBlock(bytesDownloaded: Long, totalBytes: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
        Text(
            text = stringResource(R.string.download_running),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val progress = if (totalBytes > 0) {
            (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else null
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart,
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 4.dp,
                )
            }
        }
        Text(
            text = formatBytesLabel(bytesDownloaded, totalBytes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as android.app.Application)
            DownloadViewModel(app, setupViewModel)
        }
    }
