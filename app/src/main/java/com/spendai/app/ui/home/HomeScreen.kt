package com.spendai.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.R
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.local.entity.TransactionDirection
import com.spendai.app.domain.ingestion.IngestionProgress
import com.spendai.app.domain.model.TransactionTitle
import com.spendai.app.inference.InferenceState
import com.spendai.app.ui.components.BigOutlinedButton
import com.spendai.app.ui.components.BigPrimaryButton
import com.spendai.app.ui.components.CartoonIcon
import com.spendai.app.ui.components.SectionLabel
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.ingest.IngestRangePickerDialog
import com.spendai.app.ui.setup.SetupViewModel
import com.spendai.app.ui.theme.Dimens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    setupViewModel: SetupViewModel,
    onOpenReview: () -> Unit,
    onOpenTransactions: () -> Unit,
    onOpenDebugLog: () -> Unit = {},
    onOpenSources: () -> Unit = {},
    onOpenModelSettings: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.warmUpEngine() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.home_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        CartoonIcon(id = R.drawable.ic_more_cartoon, size = 32.dp)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Sources & categories") },
                            onClick = {
                                menuOpen = false
                                onOpenSources()
                            },
                            leadingIcon = { CartoonIcon(R.drawable.ic_review_cartoon, size = 24.dp) },
                        )
                        DropdownMenuItem(
                            text = { Text("Debug log") },
                            onClick = {
                                menuOpen = false
                                onOpenDebugLog()
                            },
                            leadingIcon = { CartoonIcon(R.drawable.ic_review_cartoon, size = 24.dp) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.home_overflow_model_settings)) },
                            onClick = {
                                menuOpen = false
                                onOpenModelSettings()
                            },
                            leadingIcon = { CartoonIcon(R.drawable.ic_refresh_cartoon, size = 24.dp) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.home_overflow_rerun)) },
                            onClick = {
                                menuOpen = false
                                setupViewModel.reset()
                            },
                            leadingIcon = { CartoonIcon(R.drawable.ic_refresh_cartoon, size = 24.dp) },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd)) {
                IngestCard(
                    engineState = ui.engineState,
                    engineLabel = ui.engineLabel,
                    progress = ui.progress,
                    onPickRange = { pickerOpen = true },
                    onReprocess = viewModel::startReprocess,
                    onCancel = viewModel::cancelIngest,
                )
                RecentActivityCard(
                    transactions = ui.recentTransactions,
                    onSeeAll = onOpenTransactions,
                )
                Spacer(Modifier.size(Dimens.SpaceMd))
            }
        }
    }

    if (pickerOpen) {
        IngestRangePickerDialog(
            onConfirm = { range ->
                pickerOpen = false
                viewModel.startIngest(range)
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun IngestCard(
    engineState: InferenceState,
    engineLabel: String,
    progress: IngestionProgress,
    onPickRange: () -> Unit,
    onReprocess: () -> Unit,
    onCancel: () -> Unit,
) {
    StickerCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CartoonIcon(id = R.drawable.art_sms_mascot, size = 56.dp)
                Spacer(Modifier.size(Dimens.SpaceSm))
                Column {
                    Text(
                        text = stringResource(R.string.home_ingest_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.home_ingest_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            EngineStatusLine(engineState, engineLabel)
            ProgressBlock(progress)
            val isRunning = progress !is IngestionProgress.Idle &&
                progress !is IngestionProgress.Done &&
                progress !is IngestionProgress.Failure &&
                progress !is IngestionProgress.Cancelled
            if (isRunning) {
                BigOutlinedButton(
                    onClick = onCancel,
                    text = stringResource(R.string.onboarding_cancel),
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { CartoonIcon(R.drawable.ic_cross_cartoon, size = 28.dp) },
                )
            } else {
                BigPrimaryButton(
                    onClick = onPickRange,
                    text = stringResource(R.string.home_ingest_cta),
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { CartoonIcon(R.drawable.ic_arrow_down_cartoon, size = 28.dp) },
                )
                Spacer(Modifier.size(Dimens.SpaceXs))
                BigOutlinedButton(
                    onClick = onReprocess,
                    text = stringResource(R.string.home_reprocess_pending),
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { CartoonIcon(R.drawable.ic_refresh_cartoon, size = 24.dp) },
                )
            }
        }
    }
}

@Composable
private fun EngineStatusLine(state: InferenceState, label: String) {
    val tint = when (state) {
        is InferenceState.Ready -> MaterialTheme.colorScheme.tertiary
        is InferenceState.Busy -> MaterialTheme.colorScheme.primary
        is InferenceState.Error -> MaterialTheme.colorScheme.error
        is InferenceState.Loading -> MaterialTheme.colorScheme.primary
        is InferenceState.Uninitialized -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when (state) {
        is InferenceState.Busy -> "Engine: " + state.progress.toLabel()
        is InferenceState.Ready -> "Engine: Ready on ${state.backendLabel}"
        else -> "Engine: $label"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = tint,
    )
}

@Composable
private fun ProgressBlock(progress: IngestionProgress) {
    when (progress) {
        IngestionProgress.Idle -> Unit
        is IngestionProgress.EngineInitialising -> {
            Text(
                text = "Loading model on this device…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is IngestionProgress.LoadingFromSource -> {
            Text(
                text = "Loaded ${progress.seenSoFar} messages from inbox…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is IngestionProgress.MessageParsed -> {
            val total = progress.totalMessages
            val current = progress.messageIndex
            Text(
                text = "Parsed $current/$total",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { (current + 1).toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        is IngestionProgress.MessageCommitted -> {
            val total = progress.totalMessages
            val current = progress.messageIndex
            Text(
                text = "Committed $current/$total",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { (current + 1).toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        is IngestionProgress.MessageSkipped -> {
            val total = progress.totalMessages
            Text(
                text = "Skipped ${progress.messageIndex + 1}/$total: ${progress.reason}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { (progress.messageIndex + 1).toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        is IngestionProgress.Done -> {
            val s = progress.summary
            val parts = buildList {
                add("${s.committedTransactions} committed")
                if (s.ignored > 0) add("${s.ignored} ignored")
                if (s.skippedByA1 > 0) add("${s.skippedByA1} skipped (A1)")
                if (s.skippedByA2 > 0) add("${s.skippedByA2} skipped (A2)")
            }
            Text(
                text = "Done — " + parts.joinToString(", "),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        is IngestionProgress.Failure -> Text(
            text = "Failed: ${progress.message}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        IngestionProgress.Cancelled -> Text(
            text = "Cancelled",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentActivityCard(
    transactions: List<Transaction>,
    onSeeAll: () -> Unit,
) {
    StickerCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SectionLabel(
                    stringResource(R.string.home_recent_title),
                    modifier = Modifier.weight(1f),
                )
                if (transactions.isNotEmpty()) {
                    BigOutlinedButton(
                        onClick = onSeeAll,
                        text = stringResource(R.string.home_recent_see_all),
                    )
                }
            }
            if (transactions.isEmpty()) {
                Text(
                    text = stringResource(R.string.home_recent_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                transactions.forEach { txn -> RecentRow(txn) }
            }
        }
    }
}

@Composable
private fun RecentRow(txn: Transaction) {
    val symbol = when (txn.direction) {
        TransactionDirection.DEBIT.name -> "-"
        TransactionDirection.CREDIT.name -> "+"
        else -> ""
    }
    val amount = formatAmount(txn.amountPaise)
    val tint = when (txn.direction) {
        TransactionDirection.DEBIT.name -> MaterialTheme.colorScheme.error
        TransactionDirection.CREDIT.name -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val direction = when (txn.direction) {
        TransactionDirection.CREDIT.name -> TransactionDirection.CREDIT
        else -> TransactionDirection.DEBIT
    }
    val title = TransactionTitle.derive(
        merchantName = null,
        categoryName = null,
        direction = direction,
        channel = txn.channel,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = formatTimeOfDay(txn.txnAtMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(
                R.string.transactions_amount_format, symbol, amount, txn.currency,
            ),
            style = MaterialTheme.typography.titleMedium,
            color = tint,
        )
    }
}

private fun formatAmount(paise: Long): String {
    val abs = if (paise < 0) -paise else paise
    val rupees = abs / 100
    val p = abs % 100
    return "${"%,d".format(rupees)}.${p.toString().padStart(2, '0')}"
}

private fun formatTimeOfDay(txnAtMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(txnAtMillis).atZone(zone).toLocalTime()
    return time.format(DateTimeFormatter.ofPattern("HH:mm"))
}
