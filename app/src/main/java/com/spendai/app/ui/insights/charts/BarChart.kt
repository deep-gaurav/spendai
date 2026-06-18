package com.spendai.app.ui.insights.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spendai.app.ui.insights.format.InsightsFormat
import com.spendai.app.ui.theme.Dimens
import kotlin.math.max
import kotlin.math.min

enum class BarChartOrientation { VERTICAL, HORIZONTAL }

/**
 * A single bar in the chart. `value` is the raw magnitude
 * (e.g. paise), `label` is the row/category name, and
 * `trailingLabel` is the formatted amount rendered at the
 * bar's end. `color` overrides the palette when supplied.
 */
data class BarEntry(
    val label: String,
    val value: Long,
    val trailingLabel: String,
    val color: Color,
    val emoji: String? = null,
)

/**
 * Hand-rolled bar chart. Used by:
 *
 *  - TopMerchantsCard: VERTICAL orientation, 5 bars, each
 *    bar's trailing label is the compact amount.
 *  - DayOfWeekCard: HORIZONTAL orientation, 7 bars, each
 *    bar's trailing label is the day short name.
 *
 * All bars are scaled against the max `value` in the data
 * set. A single data point renders at 50% width so the
 * silhouette still reads as a chart.
 */
@Composable
fun BarChart(
    entries: List<BarEntry>,
    orientation: BarChartOrientation,
    modifier: Modifier = Modifier,
    barHeight: Dp = 22.dp,
    showValues: Boolean = true,
) {
    val maxValue = entries.maxOfOrNull { it.value }?.toFloat()?.coerceAtLeast(1f) ?: 1f
    val animProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 600),
        label = "barProgress",
    )
    val outline = MaterialTheme.colorScheme.outline
    val textMeasurer = rememberTextMeasurer()
    val labelStyle: TextStyle = MaterialTheme.typography.labelMedium
    val valueStyle: TextStyle = MaterialTheme.typography.labelMedium

    if (entries.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(com.spendai.app.R.string.insights_no_spend_in_window),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    when (orientation) {
        BarChartOrientation.VERTICAL -> VerticalBars(
            entries = entries,
            maxValue = maxValue,
            animProgress = animProgress,
            barHeight = barHeight,
            outline = outline,
            showValues = showValues,
            labelStyle = labelStyle,
            valueStyle = valueStyle,
            textMeasurer = textMeasurer,
            modifier = modifier,
        )
        BarChartOrientation.HORIZONTAL -> HorizontalBars(
            entries = entries,
            maxValue = maxValue,
            animProgress = animProgress,
            barHeight = barHeight,
            outline = outline,
            showValues = showValues,
            labelStyle = labelStyle,
            valueStyle = valueStyle,
            textMeasurer = textMeasurer,
            modifier = modifier,
        )
    }
}

@Composable
private fun VerticalBars(
    entries: List<BarEntry>,
    maxValue: Float,
    animProgress: Float,
    barHeight: Dp,
    outline: Color,
    showValues: Boolean,
    labelStyle: TextStyle,
    valueStyle: TextStyle,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    modifier: Modifier,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = MaterialTheme.colorScheme.onSurface

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        verticalAlignment = Alignment.Bottom,
    ) {
        entries.forEach { entry ->
            val fraction = (entry.value.toFloat() / maxValue).coerceIn(0f, 1f) * animProgress
            val fillFraction = if (entries.size == 1) 0.5f else fraction
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (showValues) {
                    Text(
                        text = entry.trailingLabel,
                        style = valueStyle,
                        color = valueColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(4.dp))
                }
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight * 5),
                ) {
                    val w = size.width
                    val h = size.height
                    val barW = w * 0.6f
                    val barH = h * fillFraction.coerceAtLeast(0.04f)
                    val left = (w - barW) / 2f
                    val top = h - barH
                    drawRoundRect(
                        color = entry.color,
                        topLeft = Offset(left, top),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(8f, 8f),
                    )
                    // Ink stroke to keep the chunky sticker look.
                    drawRoundRect(
                        color = outline,
                        topLeft = Offset(left, top),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(8f, 8f),
                        style = Stroke(width = 2f),
                    )
                }
                Spacer(Modifier.size(2.dp))
                Text(
                    text = entry.label,
                    style = labelStyle,
                    color = onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HorizontalBars(
    entries: List<BarEntry>,
    maxValue: Float,
    animProgress: Float,
    barHeight: Dp,
    outline: Color,
    showValues: Boolean,
    labelStyle: TextStyle,
    valueStyle: TextStyle,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    modifier: Modifier,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
    ) {
        entries.forEach { entry ->
            val fraction = (entry.value.toFloat() / maxValue).coerceIn(0f, 1f) * animProgress
            val fillFraction = if (entries.size == 1) 0.5f else fraction
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
            ) {
                Text(
                    text = entry.label,
                    style = labelStyle,
                    color = onSurface,
                    modifier = Modifier.width(36.dp),
                    maxLines = 1,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(barHeight),
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(barHeight),
                    ) {
                        val w = size.width
                        val h = size.height
                        val barH = h
                        val barW = max(8f, w * fillFraction.coerceAtLeast(0.02f))
                        drawRoundRect(
                            color = entry.color,
                            topLeft = Offset(0f, 0f),
                            size = Size(barW, barH),
                            cornerRadius = CornerRadius(8f, 8f),
                        )
                        drawRoundRect(
                            color = outline,
                            topLeft = Offset(0f, 0f),
                            size = Size(barW, barH),
                            cornerRadius = CornerRadius(8f, 8f),
                            style = Stroke(width = 2f),
                        )
                    }
                }
                if (showValues) {
                    Text(
                        text = entry.trailingLabel,
                        style = valueStyle,
                        color = onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(56.dp),
                    )
                }
            }
        }
    }
    // Suppress unused warnings: the parameters are wired for a future
    // tooltipping layer; keep them in the signature.
    @Suppress("UNUSED_EXPRESSION")
    min(textMeasurer.hashCode(), 0)
}
