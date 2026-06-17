package com.spendai.app.ui.ingest

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.spendai.app.R
import com.spendai.app.ui.components.BigOutlinedButton
import com.spendai.app.ui.components.BigPrimaryButton
import com.spendai.app.ui.components.CartoonIcon
import com.spendai.app.ui.components.SectionLabel
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngestRangeSheet(
    onPick: (HomeViewModelRangePreset) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ctx = LocalContext.current

    var readSmsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.READ_SMS,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        readSmsGranted = granted
    }

    val onRequestPermission: () -> Unit = {
        val activity = ctx as? Activity
        if (activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.READ_SMS,
            )
        ) {
            // The user has previously denied — surface the rationale
            // and let them tap "Grant" again to retry. The system
            // dialog is a no-op when "Don't ask again" is set, so
            // the launcher will return false in that case.
        }
        permissionLauncher.launch(Manifest.permission.READ_SMS)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd),
        ) {
            StickerCard {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                    SectionLabel(stringResource(R.string.ingest_sheet_title))
                    Text(
                        text = stringResource(R.string.ingest_sheet_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (!readSmsGranted) {
                StickerCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
                        Text(
                            text = stringResource(R.string.ingest_sheet_needs_sms_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = stringResource(R.string.ingest_sheet_needs_sms_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        BigPrimaryButton(
                            onClick = onRequestPermission,
                            text = stringResource(R.string.ingest_sheet_grant),
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = {
                                CartoonIcon(id = R.drawable.ic_bell_cartoon, size = 28.dp)
                            },
                        )
                    }
                }
            } else {
                BigPrimaryButton(
                    onClick = { onPick(HomeViewModelRangePreset.YESTERDAY) },
                    text = stringResource(R.string.ingest_range_yesterday),
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { CartoonIcon(id = R.drawable.ic_clock_cartoon, size = 28.dp) },
                )
                Text(
                    text = stringResource(R.string.ingest_range_yesterday_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.SpaceXs),
                )

                BigOutlinedButton(
                    onClick = { onPick(HomeViewModelRangePreset.LAST_7_DAYS) },
                    text = stringResource(R.string.ingest_range_week),
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { CartoonIcon(id = R.drawable.ic_clock_cartoon, size = 28.dp) },
                )
                Text(
                    text = stringResource(R.string.ingest_range_week_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.SpaceXs),
                )

                BigOutlinedButton(
                    onClick = { onPick(HomeViewModelRangePreset.LAST_30_DAYS) },
                    text = stringResource(R.string.ingest_range_month),
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { CartoonIcon(id = R.drawable.ic_clock_cartoon, size = 28.dp) },
                )
                Text(
                    text = stringResource(R.string.ingest_range_month_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.SpaceXs),
                )
            }

            Spacer(Modifier.height(Dimens.SpaceMd))
        }
    }
}

/** Re-export of the home VM's preset enum so the sheet can be tested
 *  without an AndroidViewModel dependency. */
typealias HomeViewModelRangePreset = com.spendai.app.ui.home.HomeViewModel.RangePreset
