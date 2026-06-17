package com.spendai.app.ui.debug

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.R
import com.spendai.app.data.local.entity.IngestionLogA1
import com.spendai.app.data.local.entity.IngestionLogA2
import com.spendai.app.ui.components.SectionLabel
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.theme.Dimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(
    onBack: () -> Unit,
    onRowClick: (Long) -> Unit,
    viewModel: DebugLogViewModel = viewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Debug log", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("\u2039", style = MaterialTheme.typography.headlineMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(Dimens.SpaceMd),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd),
            ) {
                Text(
                    text = "No messages have been ingested yet. Run an ingestion, then come back.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm),
            contentPadding = PaddingValues(vertical = Dimens.SpaceXs),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
        ) {
            items(rows, key = { it.log.id }) { row ->
                DebugLogRowCard(row = row, onClick = { onRowClick(row.log.id) })
            }
        }
    }
}

@Composable
private fun DebugLogRowCard(row: DebugLogRow, onClick: () -> Unit) {
    val outcome = outcomeFor(row.log.a1Outcome, row.log.a2Outcome)
    StickerCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = Dimens.SpaceXs),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
            ) {
                Text(
                    text = row.sender.ifBlank { "(unknown sender)" },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutcomeChip(outcome)
            }
            if (row.bodyPreview.isNotEmpty()) {
                Text(
                    text = row.bodyPreview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = formatTimestamp(row.log.ingestedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OutcomeChip(outcome: String) {
    val (label, color) = when (outcome) {
        "COMMITTED" -> "Committed" to MaterialTheme.colorScheme.tertiary
        "IGNORE" -> "Ignored" to MaterialTheme.colorScheme.onSurfaceVariant
        "SKIPPED_A1" -> "Skipped (A1)" to MaterialTheme.colorScheme.error
        "SKIPPED_A2" -> "Skipped (A2)" to MaterialTheme.colorScheme.error
        else -> outcome to MaterialTheme.colorScheme.onSurfaceVariant
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = color.copy(alpha = 0.15f),
            disabledLabelColor = color,
        ),
    )
}

private fun outcomeFor(a1: String, a2: String?): String = when {
    a2 == IngestionLogA2.COMMITTED -> "COMMITTED"
    a1 == IngestionLogA1.IGNORE -> "IGNORE"
    a1 == IngestionLogA1.SKIPPED_A1 -> "SKIPPED_A1"
    a2 == IngestionLogA2.SKIPPED_A2 -> "SKIPPED_A2"
    a1 == IngestionLogA1.NOT_RUN && a2 == IngestionLogA2.NOT_RUN -> "NOT_RUN"
    else -> a1
}

private fun formatTimestamp(millis: Long): String {
    val fmt = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
    return fmt.format(Date(millis))
}
