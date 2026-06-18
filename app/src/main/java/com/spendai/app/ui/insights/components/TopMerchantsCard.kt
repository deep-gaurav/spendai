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
import com.spendai.app.ui.theme.Dimens

/**
 * "Top merchants" card. Vertical bar chart with up to
 * [com.spendai.app.data.repository.InsightsRepository.DEFAULT_TOP_MERCHANTS]
 * rows. Bar height is the total DEBIT spend in the active
 * window; the label on the right is the merchant name.
 */
@Composable
fun TopMerchantsCard(
    snapshot: InsightsSnapshot,
    modifier: Modifier = Modifier,
) {
    StickerCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
            SectionLabel(stringResource(R.string.insights_card_merchants))
            if (snapshot.topMerchants.isEmpty()) {
                androidx.compose.material3.Text(
                    text = stringResource(R.string.insights_no_spend_in_window),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            val scheme = MaterialTheme.colorScheme
            val entries = snapshot.topMerchants.mapIndexed { idx, bucket ->
                BarEntry(
                    label = bucket.name.take(8),
                    value = bucket.paise,
                    trailingLabel = InsightsFormat.compactAmount(bucket.paise, snapshot.currency),
                    color = ChartPalette.forBucket(idx, scheme),
                    emoji = bucket.emoji,
                )
            }
            BarChart(
                entries = entries,
                orientation = BarChartOrientation.VERTICAL,
            )
        }
    }
}
