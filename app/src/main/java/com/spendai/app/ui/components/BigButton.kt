package com.spendai.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.spendai.app.ui.theme.Dimens

/**
 * The primary CTA. Chunky ink border, hard offset shadow at rest;
 * on press the button slides into the shadow so it visibly "clicks"
 * into place. Disabled state uses surfaceVariant as the fill so the
 * button still reads as a button but does not invite interaction.
 */
@Composable
fun BigPrimaryButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    BigButtonScaffold(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        fill = if (enabled) MaterialTheme.colorScheme.primary
               else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.size(Dimens.SpaceSm))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Outlined sibling of [BigPrimaryButton]. Same shape, border, and
 * press animation, but a surface fill so it reads as secondary.
 */
@Composable
fun BigOutlinedButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    BigButtonScaffold(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        fill = MaterialTheme.colorScheme.surface,
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.size(Dimens.SpaceSm))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun BigButtonScaffold(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier,
    fill: Color,
    content: @Composable () -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    val shape = MaterialTheme.shapes.medium
    val shadow = Dimens.ShadowMedium
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressedOffset by animateDpAsState(
        targetValue = if (isPressed) shadow else 0.dp,
        label = "button-press",
    )

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = Dimens.ButtonLarge)
            .padding(end = shadow, bottom = shadow),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(shadow, shadow)
                .background(outline, shape),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    translationX = pressedOffset.toPx()
                    translationY = pressedOffset.toPx()
                }
                .background(fill, shape)
                .border(Dimens.BorderThick, outline, shape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(horizontal = Dimens.SpaceMd),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) { content() }
        }
    }
}
