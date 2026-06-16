package com.spendai.app.ui.permissions

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.R
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

@Composable
fun PermissionsScreen(
    setupViewModel: SetupViewModel,
    onContinue: () -> Unit,
    viewModel: PermissionsViewModel = viewModel(
        factory = PermissionsViewModelFactory(setupViewModel),
    ),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permsToRequest = remember(ui.smsGranted, ui.notificationsGranted) {
        buildList {
            if (!ui.smsGranted) {
                add(Manifest.permission.RECEIVE_SMS)
                add(Manifest.permission.READ_SMS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !ui.notificationsGranted
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { result ->
            viewModel.onResult(result)
            val sms = Manifest.permission.RECEIVE_SMS
            if (result[sms] == false) {
                val activity = context as? android.app.Activity
                val canAsk = activity != null &&
                    ActivityCompat.shouldShowRequestPermissionRationale(activity, sms)
                if (!canAsk) viewModel.markSmsBlocked() else viewModel.resetBlocked()
            } else {
                viewModel.resetBlocked()
            }
        },
    )

    LaunchedEffect(permsToRequest) {
        if (permsToRequest.isNotEmpty()) {
            launcher.launch(permsToRequest)
        }
    }

    OnboardingScaffold(
        title = stringResource(R.string.app_name),
        step = 1,
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
                id = R.drawable.art_sms_mascot,
                size = 160.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dimens.SpaceSm),
            )

            Text(
                text = stringResource(R.string.permissions_title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.permissions_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionLabel(stringResource(R.string.permissions_section_required))
            PermissionRow(
                iconRes = R.drawable.ic_sms_cartoon,
                title = stringResource(R.string.permission_sms_title),
                subtitle = stringResource(R.string.permission_sms_subtitle),
                granted = ui.smsGranted,
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SectionLabel(stringResource(R.string.permissions_section_optional))
                PermissionRow(
                    iconRes = R.drawable.ic_bell_cartoon,
                    title = stringResource(R.string.permission_notifications_title),
                    subtitle = stringResource(R.string.permission_notifications_subtitle),
                    granted = ui.notificationsGranted,
                )
            }

            if (ui.smsBlocked) {
                StickerCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                        Text(
                            text = stringResource(R.string.permissions_rationale_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.permissions_rationale_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Dimens.SpaceXs))
                        BigPrimaryButton(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            },
                            text = stringResource(R.string.permissions_open_settings),
                        )
                    }
                }
            }

            Spacer(Modifier.height(Dimens.SpaceXs))

            BigPrimaryButton(
                onClick = onContinue,
                text = stringResource(R.string.onboarding_continue),
                enabled = ui.smsGranted,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!ui.smsGranted && !ui.smsBlocked) {
                Text(
                    text = stringResource(R.string.permissions_blocked_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    iconRes: Int,
    title: String,
    subtitle: String,
    granted: Boolean,
) {
    StickerCard {
        Row(verticalAlignment = Alignment.Top) {
            CartoonIcon(id = iconRes, size = 56.dp)
            Spacer(Modifier.size(Dimens.SpaceSm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Dimens.SpaceXs / 2))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(Dimens.SpaceXs))
            Box(
                modifier = Modifier.padding(top = 2.dp),
                contentAlignment = Alignment.TopEnd,
            ) {
                CartoonIcon(
                    id = if (granted) R.drawable.ic_check_cartoon
                         else R.drawable.ic_cross_cartoon,
                    size = 32.dp,
                )
            }
        }
    }
}

@Composable
private fun PermissionsViewModelFactory(
    setupViewModel: SetupViewModel,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { PermissionsViewModel(setupViewModel) }
    }
