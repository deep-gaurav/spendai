package com.spendai.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.spendai.app.ui.theme.Dimens

/**
 * Builds a tileable halftone dot brush in the given color. The brush
 * is cached per (color, dotRadius, spacing) tuple so repeated card
 * draws do not reallocate the underlying Bitmap / Shader.
 *
 * The pattern is one dot centered in a 2x spacing tile, repeated on
 * both axes. Works on all minSdk 26 devices (no RuntimeShader).
 */
@Composable
fun rememberHalftoneBrush(
    dotColor: Color,
    dotRadius: Dp = Dimens.HalftoneDotRadius,
    spacing: Dp = Dimens.HalftoneSpacing,
): Brush {
    val density = LocalDensity.current
    return remember(dotColor, dotRadius, spacing, density) {
        val rPx = with(density) { dotRadius.toPx() }
        val sPx = with(density) { spacing.toPx() }
        val tile = (sPx * 2f).toInt().coerceAtLeast(4)
        val bmp = Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            this.color = dotColor.toArgb()
        }
        canvas.drawCircle(sPx, sPx, rPx, paint)
        ShaderBrush(
            BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT),
        )
    }
}

/**
 * Paints the given halftone brush as a tiled background. The brush
 * is supplied by [rememberHalftoneBrush] so the bitmap is allocated
 * once and reused.
 */
fun Modifier.halftoneBackground(brush: Brush): Modifier = this.drawBehind {
    drawRect(brush = brush)
}
