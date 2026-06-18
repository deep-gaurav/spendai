package com.spendai.app.ui.insights.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.spendai.app.ui.theme.Dimens

/**
 * Sticker-styled input row at the bottom of the chat screen.
 * Lives outside the LazyColumn so it stays pinned while the
 * user scrolls. The send button mirrors the [BigPrimaryButton]
 * aesthetic but shrinks to a square to fit a chat bar.
 *
 * Behaviour:
 *  - Tap send -> [onSend] with the current text, then clear.
 *  - IME "send" key -> same.
 *  - While [enabled] is false (orchestrator busy) the field
 *    is still editable so the user can queue the next
 *    question, but the send button is dimmed.
 */
@Composable
fun AgenticChatInput(
    onSend: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    val outline = MaterialTheme.colorScheme.outline
    val shape = RoundedCornerShape(20.dp)
    val shadow = Dimens.ShadowSmall

    Box(modifier = modifier.fillMaxWidth().padding(end = shadow, bottom = shadow)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(shadow, shadow)
                .background(outline, shape),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, shape)
                .border(Dimens.BorderThick, outline, shape)
                .padding(horizontal = Dimens.SpaceSm, vertical = Dimens.SpaceXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                enabled = true,
                singleLine = false,
                maxLines = 4,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) {
                            Text(
                                text = "Ask about your money",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            SendButton(
                enabled = enabled && text.isNotBlank(),
                onClick = {
                    val payload = text
                    text = ""
                    onSend(payload)
                },
            )
        }
    }
}

@Composable
private fun SendButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val fill = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .background(fill, RoundedCornerShape(14.dp))
            .border(Dimens.BorderThin, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = tint,
            )
        }
    }
}

@Suppress("UNUSED_PARAMETER")
private fun keepImportForPreviews(text: String) = text
