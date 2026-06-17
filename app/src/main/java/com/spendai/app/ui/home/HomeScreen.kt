package com.spendai.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.R
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.local.entity.TransactionDirection
import com.spendai.app.domain.ingestion.IngestionProgress
import com.spendai.app.inference.InferenceState
import com.spendai.app.ui.components.BigOutlinedButton
import com.spendai.app.ui.components.BigPrimaryButton
import com.spendai.app.ui.components.CartoonIcon
import com.spendai.app.ui.components.SectionLabel
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.ingest.HomeViewModelRangePreset
import com.spendai.app.ui.ingest.IngestRangeSheet
import com.spendai.app.ui.setup.SetupViewModel
import com.spendai.app.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    setupViewModel: SetupViewModel,
    onRerunSetup: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenTransactions: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }

    // Warm up the engine as soon as the home is shown, so the user
    // doesn't wait 10s the first time they tap "Ingest".
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
                            text = { Text(stringResource(R.string.home_overflow_rerun)) },
                            onClick = {
                                menuOpen = false
                                setupViewModel.reset()
                                onRerunSetup()
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
                    onPickRange = { sheetOpen = true },
                    onCancel = viewModel::cancelIngest,
                )
                if (ui.pendingSourceCount > 0) {
                    CalloutCard(
                        icon = R.drawable.ic_review_cartoon,
                        text = stringResource(
                            R.string.home_callout_source_count,
                            ui.pendingSourceCount,
                            if (ui.pendingSourceCount == 1) "" else "s",
                        ),
                        cta = stringResource(R.string.home_callout_review_cta),
                        onClick = onOpenReview,
                    )
                }
                if (ui.pendingTxnCount > 0) {
                    CalloutCard(
                        icon = R.drawable.ic_bell_cartoon,
                        text = stringResource(
                            R.string.home_callout_txn_count,
                            ui.pendingTxnCount,
                            if (ui.pendingTxnCount == 1) "" else "s",
                        ),
                        cta = stringResource(R.string.home_callout_review_cta),
                        onClick = onOpenReview,
                    )
                }
                RecentActivityCard(
                    transactions = ui.recentTransactions,
                    onSeeAll = onOpenTransactions,
                )
                Spacer(Modifier.size(Dimens.SpaceMd))
            }
        }
    }

    if (sheetOpen) {
        IngestRangeSheet(
            onPick = { preset ->
                sheetOpen = false
                val range = viewModel.rangeFor(
                    when (preset) {
                        HomeViewModelRangePreset.YESTERDAY ->
                            HomeViewModel.RangePreset.YESTERDAY
                        HomeViewModelRangePreset.LAST_7_DAYS ->
                            HomeViewModel.RangePreset.LAST_7_DAYS
                        HomeViewModelRangePreset.LAST_30_DAYS ->
                            HomeViewModel.RangePreset.LAST_30_DAYS
                    },
                )
                viewModel.startIngest(range)
            },
            onDismiss = { sheetOpen = false },
        )
    }
}

@Composable
private fun IngestCard(
    engineState: InferenceState,
    engineLabel: String,
    progress: IngestionProgress,
    onPickRange: () -> Unit,
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
                // Real cancel — invokes IngestionService.cancel() which
                // calls engine.cancelCurrent() (the native C++ side).
                // The engine state surfaces an Error so the next run
                // re-initialises.
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
            }
        }
    }
}

/**
 * Small "Engine: …" line so the user can see the model warm-up and
 * the per-token decode progress. Drives off the new
 * [InferenceState.Ready] / [InferenceState.Busy] shapes.
 */
@Composable
private fun EngineStatusLine(state: InferenceState, label: String) {
    val tint = when (state) {
        is InferenceState.Ready -> MaterialTheme.colorScheme.tertiary
        is InferenceState.Busy -> MaterialTheme.colorScheme.primary
        is InferenceState.Error -> MaterialTheme.colorScheme.error
        is InferenceState.Loading -> MaterialTheme.colorScheme.primary
        is InferenceState.Uninitialized -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // Override the label string for the rich variants — the
    // `label` parameter carries the simpler label from the ViewModel
    // (for the Loading / Ready / Error cases), but for Busy we
    // want the per-token snapshot.
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

/**
 * The progress strip below the engine status. Three sub-states:
 *  - Loading N messages: a label and a thin indeterminate strip.
 *  - Engine initialising: "Loading model on this device…"
 *  - Per-message: a determinate LinearProgressIndicator
 *    (current = messageIndex, total = totalMessages of the day).
 *  - Per-day committing: a label "Day X — committing".
 *  - Terminal: Done / Failure / Cancelled text.
 */
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
        is IngestionProgress.DayStarting -> {
            Text(
                text = "Day ${progress.dayIndex} of ${progress.totalDays} — " +
                    "${progress.messageCount} messages",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        is IngestionProgress.MessageParsed -> {
            val total = progress.totalMessages
            val current = progress.messageIndex
            Text(
                text = "Day ${progress.dayIndex} — parsed $current/$total",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { current.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        is IngestionProgress.MessageResolved -> {
            val total = progress.totalMessages
            val current = progress.messageIndex
            Text(
                text = "Day ${progress.dayIndex} — resolved $current/$total",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { current.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        is IngestionProgress.MessageSkipped -> {
            val total = progress.totalMessages
            Text(
                text = "Day ${progress.dayIndex} — skipped ${progress.messageIndex}/$total: ${progress.reason}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { progress.messageIndex.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        is IngestionProgress.CommittingDay -> Text(
            text = "Day ${progress.dayIndex} of ${progress.totalDays} — committing…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        is IngestionProgress.DayCommitted -> Text(
            text = "Day ${progress.dayIndex} of ${progress.totalDays} — ${progress.commitCount} committed",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        is IngestionProgress.Done -> {
            val s = progress.summary
            val parts = buildList {
                add("${s.committedTransactions} committed")
                add("${s.needsReview} to review")
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
private fun CalloutCard(
    icon: Int,
    text: String,
    cta: String,
    onClick: () -> Unit,
) {
    StickerCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CartoonIcon(id = icon, size = 36.dp)
            Spacer(Modifier.size(Dimens.SpaceSm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.size(Dimens.SpaceSm))
            BigOutlinedButton(
                onClick = onClick,
                text = cta,
            )
        }
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = txn.channel ?: "—",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "#${txn.id} · ${txn.status}",
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
