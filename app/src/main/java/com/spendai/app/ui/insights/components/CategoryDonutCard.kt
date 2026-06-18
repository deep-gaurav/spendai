package com.spendai.app.ui.insights.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.spendai.app.R
import com.spendai.app.domain.insights.CategoryBucket
import com.spendai.app.domain.insights.InsightsSnapshot
import com.spendai.app.ui.components.SectionLabel
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.insights.charts.DonutChart
import com.spendai.app.ui.insights.charts.DonutSlice
import com.spendai.app.ui.insights.charts.LegendEntry
import com.spendai.app.ui.insights.format.InsightsFormat
import com.spendai.app.ui.theme.Dimens

/**
 * "Spending by category" card. Renders the donut + legend
 * inside a [StickerCard]; rolls up anything past
 * [MAX_SLICES] into an "Other" slice so the legend stays
 * scannable.
 */
private const val MAX_SLICES: Int = 6

@Composable
fun CategoryDonutCard(
    snapshot: InsightsSnapshot,
    modifier: Modifier = Modifier,
) {
    StickerCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
            SectionLabel(stringResource(R.string.insights_card_category))
            if (snapshot.categoryBreakdown.isEmpty() ||
                snapshot.categoryBreakdown.all { it.paise == 0L }
            ) {
                androidx.compose.material3.Text(
                    text = stringResource(R.string.insights_no_spend_in_window),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            val (top, rest) = splitTopAndOther(snapshot.categoryBreakdown)
            val scheme = MaterialTheme.colorScheme
            val slices = top.mapIndexed { idx, b ->
                DonutSlice(value = b.paise, color = com.spendai.app.ui.insights.charts.ChartPalette.forBucket(idx, scheme))
            } + if (rest.isNotEmpty()) {
                listOf(
                    DonutSlice(
                        value = rest.sumOf { it.paise },
                        color = com.spendai.app.ui.insights.charts.ChartPalette.forBucket(top.size, scheme),
                    ),
                )
            } else emptyList()
            val legendEntries = mutableListOf<LegendEntry>()
            top.forEachIndexed { idx, b ->
                val percent = percentOf(b.paise, snapshot.kpis.spentPaise)
                legendEntries += LegendEntry(
                    label = b.name,
                    emoji = b.emoji.ifBlank { "\uD83D\uDCB8" },
                    color = com.spendai.app.ui.insights.charts.ChartPalette.forBucket(idx, scheme),
                    compactValue = InsightsFormat.compactAmount(b.paise, snapshot.currency),
                    percent = percent,
                )
            }
            if (rest.isNotEmpty()) {
                val otherPaise = rest.sumOf { it.paise }
                val percent = percentOf(otherPaise, snapshot.kpis.spentPaise)
                legendEntries += LegendEntry(
                    label = stringResource(R.string.insights_other_slice),
                    emoji = "\uD83D\uDCB8",
                    color = com.spendai.app.ui.insights.charts.ChartPalette.forBucket(top.size, scheme),
                    compactValue = InsightsFormat.compactAmount(otherPaise, snapshot.currency),
                    percent = percent,
                )
            }
            DonutChart(
                total = snapshot.kpis.spentPaise,
                currency = snapshot.currency,
                slices = slices,
                legend = legendEntries,
            )
        }
    }
}

private fun splitTopAndOther(
    buckets: List<CategoryBucket>,
): Pair<List<CategoryBucket>, List<CategoryBucket>> {
    if (buckets.size <= MAX_SLICES) return buckets to emptyList()
    return buckets.take(MAX_SLICES - 1) to buckets.drop(MAX_SLICES - 1)
}

private fun percentOf(value: Long, total: Long): Int {
    if (total <= 0L) return 0
    return ((value.toDouble() / total.toDouble()) * 100.0 + 0.5).toInt().coerceIn(0, 100)
}
