package com.spendai.app.ui.insights.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.spendai.app.domain.agent.insights.AgenticDebugEntry
import com.spendai.app.domain.agent.insights.AgenticDebugKind
import com.spendai.app.ui.theme.Dimens

/**
 * Debug panel that sits between the chat list and the
 * input row when the user has toggled debug on. Renders
 * every [AgenticDebugEntry] the orchestrator has produced
 * this session: system prompt, conversation history, raw
 * model output, parsed actions, SQL, tool results, and
 * verifier triggers.
 *
 * The panel is fixed-height and scrolls internally so the
 * chat list above keeps its natural layout. The "Copy
 * transcript" button copies the full conversation + debug
 * log to the clipboard as plain text so the user can paste
 * it into a bug report.
 */
@Composable
fun AgenticDebugPanel(
    entries: List<AgenticDebugEntry>,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outline
    val shape = RoundedCornerShape(12.dp)
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceXs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "DEBUG",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "  -  ${entries.size} entries",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy transcript",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(Dimens.BorderThin, outline, shape)
                .padding(Dimens.SpaceXs),
        ) {
            if (entries.isEmpty()) {
                Text(
                    text = "No debug events yet. Send a message to start the trace.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Dimens.SpaceSm),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll)
                        .padding(Dimens.SpaceXs),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                ) {
                    for (entry in entries) {
                        DebugEntryRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugEntryRow(entry: AgenticDebugEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = entry.kind.name,
                style = MaterialTheme.typography.labelSmall,
                color = kindTint(entry.kind),
            )
            Text(
                text = entry.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = entry.content,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun kindTint(kind: AgenticDebugKind) = when (kind) {
    AgenticDebugKind.SYSTEM_PROMPT -> MaterialTheme.colorScheme.tertiary
    AgenticDebugKind.USER_TURN -> MaterialTheme.colorScheme.primary
    AgenticDebugKind.MODEL_REQUEST -> MaterialTheme.colorScheme.onSurfaceVariant
    AgenticDebugKind.MODEL_RESPONSE -> MaterialTheme.colorScheme.onSurface
    AgenticDebugKind.PARSED_ACTION -> MaterialTheme.colorScheme.primary
    AgenticDebugKind.TOOL_CALL -> MaterialTheme.colorScheme.secondary
    AgenticDebugKind.TOOL_RESULT -> MaterialTheme.colorScheme.secondary
    AgenticDebugKind.VERIFIER_TRIGGERED -> MaterialTheme.colorScheme.error
    AgenticDebugKind.VERIFIER_GAVE_UP -> MaterialTheme.colorScheme.error
}
