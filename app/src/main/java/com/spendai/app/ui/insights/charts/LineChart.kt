package com.spendai.app.ui.insights.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.spendai.app.R
import com.spendai.app.ui.insights.format.InsightsFormat
import com.spendai.app.ui.theme.Dimens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

/**
 * A single point in the daily-spend line chart. `value` is
 * paise; `date` is the calendar day in the caller's zone.
 * Sparse series: only days with at least one DEBIT row
 * appear in the data.
 */
data class LinePoint(
    val date: LocalDate,
    val value: Long,
)

/**
 * Hand-rolled line chart for the "Daily spend" card. The
 * caller hands over a sparse [points] list; the chart layer
 * walks the active window's calendar and treats any day
 * missing from the list as zero so the polyline is dense
 * and the gridline axis is meaningful.
 *
 * For windows longer than 14 days, dots are suppressed
 * (visual noise); the polyline + axis labels are enough.
 */
@Composable
fun LineChart(
    points: List<LinePoint>,
    firstDate: LocalDate,
    lastDate: LocalDate,
    currency: String,
    modifier: Modifier = Modifier,
) {
    val sorted = points.sortedBy { it.date }
    val totalDays = max(1L, java.time.temporal.ChronoUnit.DAYS.between(firstDate, lastDate) + 1L)
    val showDots = totalDays <= 14
    val animProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 600),
        label = "lineProgress",
    )

    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    val outline = MaterialTheme.colorScheme.outline
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    if (sorted.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(160.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.insights_no_spend_in_window),
                style = MaterialTheme.typography.bodyMedium,
                color = onSurfaceVariant,
            )
        }
        return
    }

    val maxValue = sorted.maxOf { it.value }.toFloat().coerceAtLeast(1f)
    val yTicks = listOf(0L, maxValue.toLong() / 2, maxValue.toLong())

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .padding(start = Dimens.SpaceXs, end = Dimens.SpaceXs, top = Dimens.SpaceXs, bottom = Dimens.SpaceXs),
        ) {
            val w = size.width
            val h = size.height
            val padTop = 8f
            val padBottom = 8f
            val plotH = h - padTop - padBottom

            // Y gridlines
            yTicks.forEachIndexed { idx, _ ->
                val y = padTop + plotH * (idx / (yTicks.size - 1).toFloat())
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
            }

            // Build a dense series across [firstDate, lastDate].
            val byDate = sorted.associateBy { it.date }
            val dayCount = (java.time.temporal.ChronoUnit.DAYS.between(firstDate, lastDate) + 1L).toInt()
            val dense: List<LinePoint> = (0 until dayCount).map { offset ->
                val date = firstDate.plusDays(offset.toLong())
                byDate[date] ?: LinePoint(date, 0L)
            }
            val totalSlots = max(1, dense.size - 1)
            val xStep = w / totalSlots.toFloat()
            val points2d: List<Offset> = dense.mapIndexed { idx, p ->
                val x = idx * xStep
                val y = padTop + plotH * (1f - (p.value.toFloat() / maxValue))
                Offset(x, y)
            }

            // Filled area under the line
            val filled = Path().apply {
                moveTo(points2d.first().x, h - padBottom)
                points2d.forEach { lineTo(it.x, it.y) }
                lineTo(points2d.last().x, h - padBottom)
                close()
            }
            // Clip the filled area to the animated progress so the
            // area "grows" left-to-right on first render.
            val clipRight = w * animProgress
            clipRect(left = 0f, top = 0f, right = clipRight, bottom = h) {
                drawPath(filled, color = fillColor)
            }

            // Polyline
            val line = Path().apply {
                moveTo(points2d.first().x, points2d.first().y)
                points2d.drop(1).forEach { lineTo(it.x, it.y) }
            }
            clipRect(left = 0f, top = 0f, right = clipRight, bottom = h) {
                drawPath(
                    path = line,
                    color = lineColor,
                    style = Stroke(width = 4f),
                )
            }

            // Dots (only on short windows)
            if (showDots) {
                points2d.forEach { p ->
                    drawCircle(
                        color = outline,
                        radius = 5f,
                        center = p,
                        style = Stroke(width = 2f),
                    )
                    drawCircle(
                        color = lineColor,
                        radius = 3.5f,
                        center = p,
                    )
                }
            }
        }
        // X axis labels: min, mid, max date.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.SpaceXs),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            val midDays = java.time.temporal.ChronoUnit.DAYS.between(firstDate, lastDate) / 2
            val mid = firstDate.plusDays(midDays)
            Text(
                text = fmt.format(firstDate),
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
            Text(
                text = fmt.format(mid),
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = fmt.format(lastDate),
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
        // Y axis hint: peak amount
        Text(
            text = "peak ${InsightsFormat.compactAmount(maxValue.toLong(), currency)}",
            style = MaterialTheme.typography.labelSmall,
            color = onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.SpaceXs, vertical = 2.dp),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * Helper used by the test layer. The Canvas drawing itself
 * isn't unit-testable from a pure-JVM test, but the
 * coordinate-mapping math is — so it's pulled out here as a
 * pure function.
 */
internal fun densePoints(
    sparse: List<LinePoint>,
    firstDate: LocalDate,
    lastDate: LocalDate,
): List<LinePoint> {
    val byDate = sparse.associateBy { it.date }
    val dayCount = (java.time.temporal.ChronoUnit.DAYS.between(firstDate, lastDate) + 1L).toInt()
    return (0 until dayCount).map { offset ->
        val date = firstDate.plusDays(offset.toLong())
        byDate[date] ?: LinePoint(date, 0L)
    }
}
