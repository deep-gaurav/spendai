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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spendai.app.R
import com.spendai.app.ui.insights.format.InsightsFormat
import com.spendai.app.ui.theme.Dimens

/**
 * A ring-chart slice descriptor. `value` is the raw magnitude
 * (e.g. paise). The chart layer converts to sweep angles.
 */
data class DonutSlice(
    val value: Long,
    val color: Color,
)

/**
 * A hand-rolled donut chart for the "Spending by category"
 * card. The total is shown in the center; the right-hand
 * column is a stacked legend of [legend] entries.
 *
 * The ring is drawn with one [Canvas] pass; the total label
 * and legend are normal Compose [Text] so they pick up the
 * theme's typography automatically.
 */
@Composable
fun DonutChart(
    total: Long,
    currency: String,
    slices: List<DonutSlice>,
    legend: List<LegendEntry>,
    modifier: Modifier = Modifier,
) {
    val totalValue = slices.sumOf { it.value }.toFloat().coerceAtLeast(1f)
    val sweepFraction by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 600),
        label = "donutSweep",
    )
    val outline = MaterialTheme.colorScheme.outline
    val empty = slices.isEmpty() || slices.all { it.value == 0L }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMd),
    ) {
        Box(
            modifier = Modifier.size(140.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(140.dp)) {
                val stroke = 22f
                val side = minOf(size.width, size.height) - stroke
                val topLeft = Offset(
                    x = (size.width - side) / 2f,
                    y = (size.height - side) / 2f,
                )
                val arcSize = Size(width = side, height = side)

                if (empty) {
                    // Faint background ring so the silhouette still
                    // reads as a donut in the empty case.
                    drawArc(
                        color = outline.copy(alpha = 0.12f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke),
                    )
                } else {
                    // Background ring
                    drawArc(
                        color = outline.copy(alpha = 0.12f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke),
                    )

                    // Slices
                    var startAngle = -90f
                    slices.forEach { slice ->
                        val fraction = (slice.value.toFloat() / totalValue) * sweepFraction
                        val sweep = fraction * 360f
                        if (sweep > 0f) {
                            drawArc(
                                color = slice.color,
                                startAngle = startAngle,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = stroke),
                            )
                        }
                        startAngle += sweep
                    }
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = InsightsFormat.compactAmount(total, currency),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = currency,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(Dimens.SpaceXs))
        // Legend
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        ) {
            if (legend.isEmpty() || empty) {
                Text(
                    text = stringResource(R.string.insights_no_spend_in_window),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                legend.forEach { entry -> LegendRow(entry) }
            }
        }
    }
}

@Composable
private fun LegendRow(entry: LegendEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
    ) {
        Canvas(modifier = Modifier.size(12.dp)) {
            drawCircle(color = entry.color)
        }
        Text(
            text = "${entry.emoji} ${entry.label}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${entry.compactValue} · ${entry.percent}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Single row in the donut legend. */
data class LegendEntry(
    val label: String,
    val emoji: String,
    val color: Color,
    val compactValue: String,
    val percent: Int,
)
