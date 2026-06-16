package com.spendai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spendai.app.ui.theme.Dimens

/**
 * Uppercase sticker chip with an ink border and a small offset shadow.
 * Used as a section header ("REQUIRED", "SOURCE", etc.) above cards
 * and forms.
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outline
    val shape = MaterialTheme.shapes.small
    val shadow = Dimens.ShadowSmall

    Box(modifier = modifier.padding(end = shadow, bottom = shadow)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(shadow, shadow)
                .background(outline, shape),
        )
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer, shape)
                .border(Dimens.BorderThin, outline, shape)
                .padding(horizontal = Dimens.SpaceSm, vertical = 4.dp),
        ) {
            Text(
                text = text.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
