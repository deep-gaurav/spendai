package com.spendai.app.ui.tracking

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.R
import com.spendai.app.SpendAiApp
import com.spendai.app.domain.backup.TrackingBackupManager
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.insights.format.InsightsFormat
import com.spendai.app.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Month-wise tracking history. Groups every transaction by
 * calendar month (most recent first); each card shows the
 * month's spend/income/txn totals and opens
 * [TrackingMonthDetailScreen] on tap. The overflow menu exports
 * or imports the local monthly-snapshot backup (see
 * [TrackingBackupManager] and [TrackingViewModel]).
 */
@Composable
fun TrackingScreen(
    viewModel: TrackingViewModel = viewModel(),
    onOpenMonth: (String) -> Unit = {},
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val app = context.applicationContext as SpendAiApp
            runCatching {
                val rows = withContext(Dispatchers.IO) { TrackingBackupManager.import(context, uri) }
                withContext(Dispatchers.IO) { app.monthlySnapshotRepository.upsertAll(rows) }
                rows.size
            }.onSuccess { count ->
                Toast.makeText(
                    context,
                    context.getString(R.string.tracking_import_success, count),
                    Toast.LENGTH_LONG,
                ).show()
            }.onFailure {
                Toast.makeText(context, R.string.tracking_import_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.tracking_title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.tracking_menu))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tracking_export)) },
                        onClick = {
                            menuExpanded = false
                            val app = context.applicationContext as SpendAiApp
                            scope.launch {
                                runCatching {
                                    val snapshots = withContext(Dispatchers.IO) {
                                        app.monthlySnapshotRepository.getAllOnce()
                                    }
                                    withContext(Dispatchers.IO) {
                                        TrackingBackupManager.export(context, snapshots)
                                    }
                                }.onSuccess { uri ->
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(intent, context.getString(R.string.tracking_export)),
                                    )
                                }.onFailure {
                                    Toast.makeText(context, R.string.tracking_export_error, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tracking_import)) },
                        onClick = {
                            menuExpanded = false
                            importLauncher.launch(arrayOf("application/json"))
                        },
                    )
                }
            }
        }
        if (ui.months.isEmpty()) {
            StickerCard {
                Text(
                    text = stringResource(R.string.tracking_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
                items(ui.months, key = { it.yearMonth.toString() }) { group ->
                    MonthCard(group = group, onClick = { onOpenMonth(group.yearMonth.toString()) })
                }
            }
        }
    }
}

@Composable
private fun MonthCard(
    group: TrackingMonthGroup,
    onClick: () -> Unit,
) {
    StickerCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            Text(
                text = group.yearMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            group.totals.forEach { total ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MonthStat(
                        label = stringResource(R.string.tracking_spent_label),
                        value = "${InsightsFormat.amount(total.debitPaise, total.currency)} ${total.currency}",
                        color = MaterialTheme.colorScheme.error,
                    )
                    MonthStat(
                        label = stringResource(R.string.tracking_income_label),
                        value = "${InsightsFormat.amount(total.creditPaise, total.currency)} ${total.currency}",
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    MonthStat(
                        label = stringResource(R.string.tracking_txns_label),
                        value = total.txnCount.toString(),
                        color = MaterialTheme.colorScheme.onSurface,
                        alignEnd = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthStat(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    alignEnd: Boolean = false,
) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
    }
}
