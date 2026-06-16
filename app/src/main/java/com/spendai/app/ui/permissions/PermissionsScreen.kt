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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HighlightOff
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.R
import com.spendai.app.ui.setup.SetupViewModel
import com.spendai.app.ui.theme.SpendAiTheme
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                stringResource(R.string.permissions_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.permissions_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader(stringResource(R.string.permissions_section_required))
            PermissionRow(
                icon = Icons.Outlined.MailOutline,
                title = stringResource(R.string.permission_sms_title),
                subtitle = stringResource(R.string.permission_sms_subtitle),
                granted = ui.smsGranted,
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SectionHeader(stringResource(R.string.permissions_section_optional))
                PermissionRow(
                    icon = Icons.Outlined.NotificationsActive,
                    title = stringResource(R.string.permission_notifications_title),
                    subtitle = stringResource(R.string.permission_notifications_subtitle),
                    granted = ui.notificationsGranted,
                )
            }

            if (ui.smsBlocked) {
                Text(
                    stringResource(R.string.permissions_rationale_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.permissions_rationale_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    },
                ) {
                    Text(stringResource(R.string.permissions_open_settings))
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onContinue,
                enabled = ui.smsGranted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_continue))
            }

            if (!ui.smsGranted && !ui.smsBlocked) {
                Text(
                    stringResource(R.string.permissions_blocked_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.padding(top = 2.dp)) {
                Icon(
                    if (granted) Icons.Outlined.CheckCircle else Icons.Outlined.HighlightOff,
                    contentDescription = null,
                    tint = if (granted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PreviewPlaceholder() {
    SpendAiTheme {
        Surface(Modifier.fillMaxSize()) {
            Text("Permissions preview")
        }
    }
}

@Composable
private fun PermissionsViewModelFactory(
    setupViewModel: SetupViewModel,
): androidx.lifecycle.ViewModelProvider.Factory =
    androidx.lifecycle.viewmodel.viewModelFactory {
        initializer { PermissionsViewModel(setupViewModel) }
    }
