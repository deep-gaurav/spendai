package com.spendai.app.ui.insights.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.spendai.app.R
import com.spendai.app.domain.insights.InsightsSnapshot
import com.spendai.app.ui.components.SectionLabel
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.insights.charts.BarChart
import com.spendai.app.ui.insights.charts.BarChartOrientation
import com.spendai.app.ui.insights.charts.BarEntry
import com.spendai.app.ui.insights.charts.ChartPalette
import com.spendai.app.ui.insights.format.InsightsFormat
import com.spendai.app.ui.insights.format.dayOfWeekShort
import com.spendai.app.ui.theme.Dimens

/**
 * "Spending by day of week" card. Horizontal bar chart,
 * 7 entries (Mon..Sun) in a fixed order, with a compact
 * amount on the right of each bar.
 */
@Composable
fun DayOfWeekCard(
    snapshot: InsightsSnapshot,
    modifier: Modifier = Modifier,
) {
    StickerCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
            SectionLabel(stringResource(R.string.insights_card_dow))
            if (snapshot.dayOfWeekSeries.all { it.paise == 0L }) {
                androidx.compose.material3.Text(
                    text = stringResource(R.string.insights_no_spend_in_window),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            val scheme = MaterialTheme.colorScheme
            val entries = snapshot.dayOfWeekSeries.map { bucket ->
                BarEntry(
                    label = stringResource(dayOfWeekShort(bucket.dayOfWeek)),
                    value = bucket.paise,
                    trailingLabel = InsightsFormat.compactAmount(bucket.paise, snapshot.currency),
                    color = ChartPalette.forBucket(bucket.dayOfWeek - 1, scheme),
                    emoji = null,
                )
            }
            BarChart(
                entries = entries,
                orientation = BarChartOrientation.HORIZONTAL,
                showValues = true,
            )
        }
    }
}
