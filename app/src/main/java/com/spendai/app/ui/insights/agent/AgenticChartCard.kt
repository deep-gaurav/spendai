package com.spendai.app.ui.insights.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spendai.app.R
import com.spendai.app.domain.agent.insights.AgenticChart
import com.spendai.app.ui.components.SectionLabel
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.insights.charts.BarChart
import com.spendai.app.ui.insights.charts.BarChartOrientation
import com.spendai.app.ui.insights.charts.BarEntry
import com.spendai.app.ui.insights.charts.ChartPalette
import com.spendai.app.ui.insights.charts.DonutChart
import com.spendai.app.ui.insights.charts.DonutSlice
import com.spendai.app.ui.insights.charts.LegendEntry
import com.spendai.app.ui.insights.charts.LineChart
import com.spendai.app.ui.insights.charts.LinePoint
import com.spendai.app.ui.theme.Dimens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Renders one [AgenticChart] inline under an assistant turn.
 * The conversion from the agentic spec to the existing chart
 * composables is intentionally thin: the existing charts own
 * their animation, palette selection, and accessibility, and
 * the agentic flow should not fork a second rendering
 * pipeline.
 *
 * Unknown / unparseable chart variants render as a labelled
 * error block rather than a silent blank card so the user
 * knows the model emitted something we cannot show.
 */
@Composable
fun AgenticChartCard(
    chart: AgenticChart,
    modifier: Modifier = Modifier,
) {
    StickerCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
            SectionLabel(chart.title)
            when (chart) {
                is AgenticChart.Donut -> DonutBody(chart)
                is AgenticChart.BarVertical -> BarBody(chart, BarChartOrientation.VERTICAL)
                is AgenticChart.BarHorizontal -> BarBody(chart, BarChartOrientation.HORIZONTAL)
                is AgenticChart.Line -> LineBody(chart)
            }
        }
    }
}

@Composable
private fun DonutBody(chart: AgenticChart.Donut) {
    val scheme = MaterialTheme.colorScheme
    val totalValue = chart.slices.sumOf { it.value }.toLong()
    val slices = chart.slices.mapIndexed { idx, s ->
        DonutSlice(
            value = (s.value * 100).toLong(),
            color = ChartPalette.forBucket(idx, scheme),
        )
    }
    val legend = chart.slices.mapIndexed { idx, s ->
        LegendEntry(
            label = s.label,
            emoji = s.emoji ?: "\uD83D\uDCB8",
            color = ChartPalette.forBucket(idx, scheme),
            compactValue = formatRupee(s.value, chart.currency),
            percent = percentOf(s.value, chart.sizesSum()),
        )
    }
    DonutChart(
        total = totalValue,
        currency = chart.currency,
        slices = slices,
        legend = legend,
    )
    Text(
        text = chart.totalLabel,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier,
    )
}

@Composable
private fun BarBody(chart: AgenticChart.BarVertical, orientation: BarChartOrientation) {
    BarBodyInternal(
        entries = chart.entries.map { BarEntryRow(it.label, it.value, it.trailingLabel, it.emoji) },
        currency = chart.currency,
        orientation = orientation,
    )
}

@Composable
private fun BarBody(chart: AgenticChart.BarHorizontal, orientation: BarChartOrientation) {
    BarBodyInternal(
        entries = chart.entries.map { BarEntryRow(it.label, it.value, it.trailingLabel, it.emoji) },
        currency = chart.currency,
        orientation = orientation,
    )
}

/**
 * Bar entry with the common shape the renderer needs. The
 * agentic [AgenticChart.BarVertical.Entry] and
 * [AgenticChart.BarHorizontal.Entry] are distinct Kotlin
 * types even though they share fields, so this internal row
 * lets us share one renderer.
 */
private data class BarEntryRow(
    val label: String,
    val value: Double,
    val trailingLabel: String?,
    val emoji: String?,
)

@Composable
private fun BarBodyInternal(
    entries: List<BarEntryRow>,
    currency: String,
    orientation: BarChartOrientation,
) {
    val scheme = MaterialTheme.colorScheme
    val chartEntries = entries.mapIndexed { idx, e ->
        BarEntry(
            label = e.label.take(12),
            value = (e.value * 100).toLong(),
            trailingLabel = e.trailingLabel ?: formatRupee(e.value, currency),
            color = ChartPalette.forBucket(idx, scheme),
            emoji = e.emoji,
        )
    }
    BarChart(
        entries = chartEntries,
        orientation = orientation,
    )
}

@Composable
private fun LineBody(chart: AgenticChart.Line) {
    val parsed = chart.points.mapNotNull { p ->
        val date = tryParseDate(p.x)
        date?.let { LinePoint(date = it, value = (p.y * 100).toLong()) }
    }
    if (parsed.isNotEmpty()) {
        val first = parsed.first().date
        val last = parsed.last().date
        LineChart(
            points = parsed,
            firstDate = first,
            lastDate = last,
            currency = chart.currency,
        )
    } else {
        // Fall back to a text summary if dates were not
        // parseable. Better than dropping the chart silently.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            chart.points.forEach { p ->
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
                    Text(
                        text = p.x,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatRupee(p.y, chart.currency),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private fun AgenticChart.Donut.sizesSum(): Double = slices.sumOf { it.value }

private fun percentOf(value: Double, total: Double): Int {
    if (total <= 0.0) return 0
    return ((value / total) * 100.0 + 0.5).toInt().coerceIn(0, 100)
}

private fun tryParseDate(label: String): LocalDate? {
    val candidates = listOf(
        "d MMM", "d MMM yyyy", "MMM d", "MMM d yyyy",
        "yyyy-MM-dd", "d/MM", "d/M/yyyy",
    )
    for (pattern in candidates) {
        try {
            return LocalDate.parse(label, DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
        } catch (_: DateTimeParseException) {
            // try the next pattern
        }
    }
    return null
}

private fun formatRupee(value: Double, currency: String): String {
    val abs = kotlin.math.abs(value)
    val sign = if (value < 0) "-" else ""
    return when {
        currency.equals("INR", ignoreCase = true) -> when {
            abs < 1_000.0 -> String.format(Locale.getDefault(), "$sign\u20B9%.0f", abs)
            abs < 100_000.0 -> String.format(Locale.getDefault(), "$sign\u20B9%.1fk", abs / 1_000.0)
            abs < 10_000_000.0 -> String.format(Locale.getDefault(), "$sign\u20B9%.1fL", abs / 100_000.0)
            else -> String.format(Locale.getDefault(), "$sign\u20B9%.1fCr", abs / 10_000_000.0)
        }
        else -> when {
            abs < 1_000_000.0 -> String.format(Locale.getDefault(), "$sign%.1fk", abs / 1_000.0)
            else -> String.format(Locale.getDefault(), "$sign%.1fM", abs / 1_000_000.0)
        }
    }
}

