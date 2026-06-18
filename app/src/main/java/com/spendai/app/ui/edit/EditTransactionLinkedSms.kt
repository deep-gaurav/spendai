package com.spendai.app.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spendai.app.data.local.dao.LinkedSmsRow
import com.spendai.app.ui.components.SectionLabel
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.theme.Dimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the linked-SMS view for the transaction: source, any
 * A2/DUPLICATE-marked SMSes for this transaction, and any
 * transaction_link edges. Each row carries a relation chip so the
 * user can see at a glance which decision the model made.
 */
@Composable
fun LinkedSmsCard(rows: List<LinkedSmsRow>) {
    if (rows.isEmpty()) return
    StickerCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
            SectionLabel("Linked SMS")
            Text(
                text = "Messages A2/A3 grouped with this one. Tap Reprompt to re-decide with your own instructions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            rows.forEach { row ->
                LinkedSmsRowCard(row)
            }
        }
    }
}

@Composable
private fun LinkedSmsRowCard(row: LinkedSmsRow) {
    val (label, color) = when (row.relation) {
        "SOURCE" -> "Source" to MaterialTheme.colorScheme.primary
        "DUPLICATE" -> "Marked as duplicate" to MaterialTheme.colorScheme.error
        "LINKED" -> ("Linked (" + (row.linkType ?: "?") + ")") to MaterialTheme.colorScheme.tertiary
        else -> row.relation to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
        ) {
            Text(
                text = row.senderAddress.ifBlank { "(unknown sender)" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
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
        Text(
            text = formatShort(row.timestamp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = row.msgBody,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Dialog for the user to type a custom instruction for A3. The
 * parent owns the running state; the dialog just collects the
 * prompt and forwards to onSubmit.
 */
@Composable
fun RepromptDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    running: Boolean,
    errorMessage: String?,
) {
    var promptText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reprompt A3") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm)) {
                Text(
                    text = "Type a free-form instruction for the auditor. It applies to this transaction and every SMS in the linked view, and is saved for future runs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    label = { Text("Your instruction") },
                    minLines = 3,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(promptText) },
                enabled = !running && promptText.isNotBlank(),
            ) { Text(if (running) "Running..." else "Reprompt") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !running) { Text("Cancel") }
        },
    )
}

private fun formatShort(millis: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(millis))
