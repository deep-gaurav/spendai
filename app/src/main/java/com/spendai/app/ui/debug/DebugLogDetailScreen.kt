package com.spendai.app.ui.debug

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.ui.components.SectionLabel
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.theme.Dimens
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogDetailScreen(
    logId: Long,
    onBack: () -> Unit,
    viewModel: DebugLogViewModel = viewModel(),
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var state by remember { mutableStateOf(DebugLogDetailState()) }
    LaunchedEffect(logId) {
        state = viewModel.loadDetail(logId)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Debug detail", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("\u2039", style = MaterialTheme.typography.headlineMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
            when {
                state.loading -> Text(
                    "Loading…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                state.notFound -> Text(
                    "Log entry not found.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                else -> {
                    val log = state.log!!
                    val raw = state.rawSms
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd)) {
                        SummaryHeader(log)
                        SmsCard(raw)
                        AgentCard(
                            label = "Agent 1",
                            prompt = log.a1Prompt,
                            response = log.a1Response,
                            outcome = log.a1Outcome,
                            confidence = log.a1Confidence,
                            error = log.a1Error,
                        )
                        AgentCard(
                            label = "Resolver & Auditor (A2 & A3)",
                            prompt = log.a2Prompt,
                            response = log.a2Response,
                            outcome = log.a2Outcome,
                            confidence = log.a2Confidence,
                            error = log.a2Error,
                            transactionId = log.transactionId,
                        )
                        Spacer(Modifier.size(Dimens.SpaceMd))
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryHeader(
    log: com.spendai.app.data.local.entity.IngestionLog,
) {
    StickerCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            Text(
                text = "Ingested ${formatTimestamp(log.ingestedAt)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "A1: ${log.a1Outcome}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (log.a2Outcome != null) {
                    Spacer(Modifier.size(Dimens.SpaceSm))
                    Text(
                        text = "A2: ${log.a2Outcome}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (log.transactionId != null) {
                Text(
                    text = "Transaction #${log.transactionId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SmsCard(raw: com.spendai.app.data.local.entity.RawSmsMessage?) {
    StickerCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            SectionLabel("SMS")
            if (raw == null) {
                Text(
                    text = "(row was deleted)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                KeyValue("Sender", raw.senderAddress)
                KeyValue("Body", raw.msgBody)
                KeyValue("Received", formatTimestamp(raw.timestamp))
            }
        }
    }
}

@Composable
private fun AgentCard(
    label: String,
    prompt: String?,
    response: String?,
    outcome: String?,
    confidence: Float?,
    error: String?,
    transactionId: Long? = null,
) {
    StickerCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(label, modifier = Modifier.weight(1f))
                if (outcome != null) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(outcome, style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(
                            disabledContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            disabledLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                    )
                }
            }
            if (confidence != null) {
                KeyValue("Confidence", "%.2f".format(confidence))
            }
            if (transactionId != null) {
                KeyValue("Transaction", "#$transactionId")
            }
            if (error != null) {
                Text(
                    text = "Error: $error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (prompt == null && response == null) {
                Text(
                    text = "(no prompt/response captured — unexpected; check the error above for what went wrong)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                CollapsibleText("Prompt", prompt ?: "(not captured for this run)")
                HorizontalDivider()
                CollapsibleText("Response", response ?: "(not captured for this run)")
            }
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CollapsibleText(label: String, value: String) {
    var expanded by remember { mutableStateOf(true) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "Hide" else "Show",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable { expanded = !expanded },
            )
        }
        if (expanded) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
    }
}



private fun formatTimestamp(millis: Long): String {
    val fmt = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
    return fmt.format(Date(millis))
}
