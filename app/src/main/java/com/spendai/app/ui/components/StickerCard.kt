package com.spendai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.spendai.app.ui.theme.Dimens

/**
 * The signature SpendAI card: thick ink border, solid offset shadow,
 * and a halftone dot pattern painted behind the content. The visual
 * footprint is (card size) + (shadow offset) on the bottom-end so
 * call sites that use [Modifier.fillMaxWidth] still get the shadow
 * bleeding to the right edge of the layout.
 */
@Composable
fun StickerCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(Dimens.SpaceMd),
    content: @Composable () -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surface
    val shape = MaterialTheme.shapes.large
    val shadow = Dimens.ShadowMedium
    val halftone = rememberHalftoneBrush(outline.copy(alpha = 0.18f))

    Box(
        modifier = modifier.padding(end = shadow, bottom = shadow),
    ) {
        // Offset shadow behind the card
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(shadow, shadow)
                .background(outline, shape),
        )
        // Card surface
        Box(
            modifier = Modifier
                .background(surface, shape)
                .halftoneBackground(halftone)
                .border(Dimens.BorderThick, outline, shape)
                .padding(contentPadding),
        ) {
            content()
        }
    }
}
