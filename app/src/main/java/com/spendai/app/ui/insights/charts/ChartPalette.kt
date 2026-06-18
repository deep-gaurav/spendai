package com.spendai.app.ui.insights.charts

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The 8-color palette used by every chart on the Insights
 * screen. Indexes are deterministic so a given
 * `CategoryBucket` always gets the same color across the
 * donut, the legend, and the top-merchants bar.
 *
 * Theme colors are picked so the bright (primary, secondary,
 * tertiary) trio is the visual focus and the
 * *Container variants act as the "fill" of donut/line/bar
 * shapes — that way the chart's silhouette matches the
 * sticker-card aesthetic of the rest of the app.
 */
object ChartPalette {

    fun colors(scheme: ColorScheme): List<Color> = listOf(
        scheme.primary,
        scheme.secondary,
        scheme.tertiary,
        scheme.primaryContainer,
        scheme.secondaryContainer,
        scheme.tertiaryContainer,
        scheme.error,
        scheme.outline,
    )

    fun colorAt(index: Int, scheme: ColorScheme): Color {
        val list = colors(scheme)
        return list[((index % list.size) + list.size) % list.size]
    }

    /**
     * Map a stable bucket index to a palette color. The caller
     * is responsible for the index — usually the bucket's
     * position in the sorted list.
     */
    fun forBucket(bucketIndex: Int, scheme: ColorScheme): Color =
        colorAt(bucketIndex, scheme)
}
