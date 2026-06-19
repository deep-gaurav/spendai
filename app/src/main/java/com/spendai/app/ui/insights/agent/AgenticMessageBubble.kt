package com.spendai.app.ui.insights.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.spendai.app.domain.agent.insights.AgenticAction
import com.spendai.app.domain.agent.insights.AgenticInsightsMessage
import com.spendai.app.domain.agent.insights.AssistantStatus
import com.spendai.app.domain.agent.insights.ToolCallStatus
import com.spendai.app.ui.theme.Dimens

/**
 * The single message-row composable. Renders one
 * [AgenticInsightsMessage] variant with the SpendAI sticker
 * aesthetic. User / assistant bubbles are right- / left-aligned
 * so the conversation reads naturally. Tool calls and tool
 * results span the full width with a distinct monospace
 * treatment for the SQL.
 */
@Composable
fun AgenticMessageBubble(
    message: AgenticInsightsMessage,
    modifier: Modifier = Modifier,
) {
    when (message) {
        is AgenticInsightsMessage.UserMessage -> UserBubble(message, modifier)
        is AgenticInsightsMessage.AssistantMessage -> AssistantBubble(message, modifier)
        is AgenticInsightsMessage.ToolCallMessage -> ToolCallBubble(message, modifier)
        is AgenticInsightsMessage.ToolResultMessage -> ToolResultBubble(message, modifier)
        is AgenticInsightsMessage.MutationToolCallMessage -> MutationToolCallBubble(message, modifier)
        is AgenticInsightsMessage.MutationToolResultMessage -> MutationToolResultBubble(message, modifier)
        is AgenticInsightsMessage.SystemMessage -> SystemBubble(message, modifier)
        is AgenticInsightsMessage.VerifierMessage -> VerifierBubble(message, modifier)
        is AgenticInsightsMessage.InternalNudge -> InternalNudgeBubble(message, modifier)
    }
}

/**
 * Verifier prompts are shown as a left-aligned warning-
 * coloured bubble so the user can see when the
 * orchestrator is re-prompting the model. They look like
 * a system message but with an explicit "Verifier" tag
 * so the audit trail is obvious.
 */
@Composable
private fun VerifierBubble(
    msg: AgenticInsightsMessage.VerifierMessage,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .border(Dimens.BorderThin, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                .padding(horizontal = Dimens.SpaceSm, vertical = Dimens.SpaceXs),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Verifier - attempt ${msg.attempt}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = msg.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * Parse-failure nudges share the warning-coloured treatment
 * with [VerifierBubble] but carry a "Parser retry" label so
 * the audit trail is obvious. The text is what the
 * orchestrator injected into the model's input as a `user`
 * turn; the model sees "Parser retry: <text>" while the
 * user sees the body prefixed by the label.
 */
@Composable
private fun InternalNudgeBubble(
    msg: AgenticInsightsMessage.InternalNudge,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .border(Dimens.BorderThin, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                .padding(horizontal = Dimens.SpaceSm, vertical = Dimens.SpaceXs),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Parser retry",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = msg.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun UserBubble(
    msg: AgenticInsightsMessage.UserMessage,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primary)
                .border(Dimens.BorderThin, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                .padding(horizontal = Dimens.SpaceSm, vertical = Dimens.SpaceXs),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = msg.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun AssistantBubble(
    msg: AgenticInsightsMessage.AssistantMessage,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
    ) {
        when (val parsed = msg.parsed) {
            is AgenticAction.Answer -> {
                if (parsed.text.isNotBlank()) {
                    TextBubble(
                        text = parsed.text,
                        background = MaterialTheme.colorScheme.surface,
                    )
                }
                parsed.charts.forEach { chart ->
                    AgenticChartCard(chart = chart)
                }
                if (parsed.text.isBlank() && parsed.charts.isEmpty()) {
                    TextBubble(
                        text = "(empty answer)",
                        background = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
            is AgenticAction.QueryDatabase -> {
                StreamingThinkingBubble(
                    text = msg.streamedText,
                    showSpinner = msg.status == AssistantStatus.Streaming,
                )
            }
            is AgenticAction.MutateMerchant -> {
                StreamingThinkingBubble(
                    text = msg.streamedText,
                    showSpinner = msg.status == AssistantStatus.Streaming,
                )
            }
            null -> when (msg.status) {
                is AssistantStatus.Streaming,
                is AssistantStatus.AwaitingParse,
                -> StreamingThinkingBubble(
                    text = msg.streamedText,
                    showSpinner = msg.status is AssistantStatus.Streaming,
                )
                is AssistantStatus.ParseFailed -> {
                    TextBubble(
                        text = msg.streamedText.ifBlank { "(empty response)" },
                        background = MaterialTheme.colorScheme.errorContainer,
                    )
                    TextBubble(
                        text = msg.status.reason,
                        background = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                is AssistantStatus.Complete -> {
                    TextBubble(
                        text = msg.streamedText.ifBlank { "(empty response)" },
                        background = MaterialTheme.colorScheme.surface,
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamingThinkingBubble(text: String, showSpinner: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .width(14.dp),
                strokeWidth = 2.dp,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(Dimens.BorderThin, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .padding(horizontal = Dimens.SpaceSm, vertical = Dimens.SpaceXs),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "thinking",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = text.ifBlank { "(waiting for model)" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun TextBubble(
    text: String,
    background: androidx.compose.ui.graphics.Color,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(Dimens.BorderThin, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(horizontal = Dimens.SpaceSm, vertical = Dimens.SpaceXs),
    )
}

@Composable
private fun ToolCallBubble(
    msg: AgenticInsightsMessage.ToolCallMessage,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        ) {
            when (val s = msg.status) {
                is ToolCallStatus.Running -> CircularProgressIndicator(
                    modifier = Modifier.width(14.dp),
                    strokeWidth = 2.dp,
                )
                is ToolCallStatus.Complete -> Text(
                    text = "+",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                is ToolCallStatus.Failed -> Text(
                    text = "!",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = "query_database",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (msg.status is ToolCallStatus.Failed) {
                Text(
                    text = " (failed)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Text(
            text = msg.thought,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 22.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(Dimens.SpaceXs),
        ) {
            Text(
                text = msg.sql,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun ToolResultBubble(
    msg: AgenticInsightsMessage.ToolResultMessage,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = buildString {
                append(if (msg.error != null) "Error" else "Result")
                append(" - ")
                append(msg.rowCount.toString())
                append(if (msg.rowCount == 1) " row" else " rows")
                if (msg.truncated) append(" (truncated)")
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (msg.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val preview = if (msg.error != null) {
            msg.error
        } else {
            renderRowsPreview(msg.columns, msg.rowCount)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(Dimens.BorderThin, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .padding(Dimens.SpaceXs),
        ) {
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun SystemBubble(
    msg: AgenticInsightsMessage.SystemMessage,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceXs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = msg.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun renderRowsPreview(columns: List<String>, rowCount: Int): String {
    if (rowCount == 0) return "(no rows)"
    val headerLine = columns.take(6).joinToString(" | ")
    return "$headerLine\n(${rowCount} rows; the full result has been sent back to the model)"
}

/**
 * Compact bubble for a merchant-mutation tool call. Renders
 * a short summary of the action (which merchant, which
 * field changes) so the user sees what the model is about
 * to commit before it actually runs. The full details live
 * in the matching [MutationToolResultBubble] below.
 */
@Composable
private fun MutationToolCallBubble(
    msg: AgenticInsightsMessage.MutationToolCallMessage,
    modifier: Modifier,
) {
    val a = msg.action
    val summary = buildString {
        append("merchant \"")
        append(a.matchByName ?: a.matchById?.toString() ?: "?")
        append("\"")
        if (a.setIsSelf == true) append(" -> isSelf=true")
        if (a.clearIsSelf == true) append(" -> isSelf=false")
        if (a.addMetadata.isNotEmpty()) {
            append("; add ")
            append(a.addMetadata.joinToString(", ") { "${it.kind}=${it.value}" })
        }
        if (a.removeMetadata.isNotEmpty()) {
            append("; remove kinds ")
            append(a.removeMetadata.joinToString(", "))
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        ) {
            when (val s = msg.status) {
                is ToolCallStatus.Running -> CircularProgressIndicator(
                    modifier = Modifier.width(14.dp),
                    strokeWidth = 2.dp,
                )
                is ToolCallStatus.Complete -> Text(
                    text = "+",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                is ToolCallStatus.Failed -> Text(
                    text = "!",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = "mutate_merchant",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (msg.status is ToolCallStatus.Failed) {
                Text(
                    text = " (failed)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Text(
            text = a.thought,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 22.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(Dimens.SpaceXs),
        ) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * Result of a merchant-mutation tool call. Surfaces the
 * counters from [com.spendai.app.domain.agent.insights.MerchantMutator.MutationResult]
 * in a human-readable form so the user can see what was
 * actually saved and how many reprompts were enqueued.
 */
@Composable
private fun MutationToolResultBubble(
    msg: AgenticInsightsMessage.MutationToolResultMessage,
    modifier: Modifier,
) {
    val r = msg.result
    val headerColor = if (r.error != null) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val summary = buildString {
        if (r.error != null) {
            append("Error: ").append(r.error)
        } else {
            append("Matched ")
                .append(r.matchedMerchantName ?: "<none>")
                .append(" (id=").append(r.matchedMerchantId ?: -1L).append(")")
            if (r.isSelfChanged) {
                append("; isSelf -> ").append(r.isSelfNewValue)
            }
            if (r.metadataAdded.isNotEmpty()) {
                append("; +")
                append(r.metadataAdded.joinToString(",") { "${it.kind}=${it.value}" })
            }
            if (r.metadataRemoved.isNotEmpty()) {
                append("; -")
                append(r.metadataRemoved.joinToString(","))
            }
            if (r.affectedTransactionIds.isNotEmpty()) {
                append("; affected=").append(r.affectedTransactionIds.size)
                if (r.selfTransferLinksWritten > 0) {
                    append(", self-links=").append(r.selfTransferLinksWritten)
                }
                if (r.repromptsEnqueued > 0) {
                    append(", reprompts=").append(r.repromptsEnqueued)
                }
            }
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = if (r.error != null) "Result - error" else "Result - saved",
            style = MaterialTheme.typography.labelMedium,
            color = headerColor,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(Dimens.BorderThin, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .padding(Dimens.SpaceXs),
        ) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
