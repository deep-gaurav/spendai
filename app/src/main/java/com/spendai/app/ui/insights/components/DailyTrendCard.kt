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
import com.spendai.app.ui.insights.charts.LineChart
import com.spendai.app.ui.insights.charts.LinePoint
import com.spendai.app.ui.theme.Dimens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * "Daily spend" card. The card receives a [snapshot] which
 * contains sparse [com.spendai.app.domain.insights.DailyBucket]s
 * (one per day with at least one DEBIT row); the line chart
 * itself densifies the series so the polyline spans the full
 * active window.
 */
@Composable
fun DailyTrendCard(
    snapshot: InsightsSnapshot,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    StickerCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs)) {
            SectionLabel(stringResource(R.string.insights_card_daily))
            if (snapshot.dailySeries.isEmpty()) {
                androidx.compose.material3.Text(
                    text = stringResource(R.string.insights_no_spend_in_window),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            val first = Instant.ofEpochMilli(snapshot.range.start).atZone(zone).toLocalDate()
            val last = Instant.ofEpochMilli(snapshot.range.end - 1L).atZone(zone).toLocalDate()
            val points = snapshot.dailySeries.map { LinePoint(date = it.date, value = it.paise) }
            LineChart(
                points = points,
                firstDate = first,
                lastDate = last,
                currency = snapshot.currency,
            )
        }
    }
}

@Suppress("UNUSED_PARAMETER")
private fun unused(last: LocalDate): LocalDate = last
