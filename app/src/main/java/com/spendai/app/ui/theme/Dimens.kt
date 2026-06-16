package com.spendai.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Brand dimensions. All UI components read borders, shadow offsets,
 * and button heights from here so the look stays consistent.
 */
object Dimens {
    // Bold borders. Outline + 3dp reads as a cartoon ink stroke; the
    // 4dp chunky width is reserved for hero stickers and the primary
    // CTA outline.
    val BorderThin = 2.dp
    val BorderThick = 3.dp
    val BorderChunky = 4.dp

    // Hard offset shadows. The shadow is a solid block, no blur - we
    // draw it via Modifier.drawBehind in StickerCard.
    val ShadowSmall = 2.dp
    val ShadowMedium = 4.dp
    val ShadowLarge = 6.dp

    // Buttons are tall and chunky. The press animation collapses the
    // shadow to 0dp so the button visually "sinks" on tap.
    val ButtonLarge = 64.dp
    val ButtonMedium = 52.dp

    // Spacing scale.
    val SpaceXs = 8.dp
    val SpaceSm = 12.dp
    val SpaceMd = 20.dp
    val SpaceLg = 28.dp
    val SpaceXl = 36.dp

    // Halftone dot pattern tuning.
    val HalftoneDotRadius = 1.5.dp
    val HalftoneSpacing = 7.dp
}
