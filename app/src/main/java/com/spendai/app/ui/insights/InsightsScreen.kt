package com.spendai.app.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendai.app.R
import com.spendai.app.ui.components.CartoonIcon
import com.spendai.app.ui.components.SectionLabel
import com.spendai.app.ui.components.StickerCard
import com.spendai.app.ui.insights.components.CategoryDonutCard
import com.spendai.app.ui.insights.components.DailyTrendCard
import com.spendai.app.ui.insights.components.DayOfWeekCard
import com.spendai.app.ui.insights.components.InsightsWindowSelector
import com.spendai.app.ui.insights.components.KpiStrip
import com.spendai.app.ui.insights.components.TopMerchantsCard
import com.spendai.app.ui.insights.format.InsightsFormat
import com.spendai.app.ui.theme.Dimens
import java.time.ZoneId

/**
 * The auto insights screen. Always-on, fixed-schema view over
 * the user's `spend_transaction` data.
 *
 * Layout: vertical scroll containing (in order)
 *   1. Header with title + mascot
 *   2. Window selector (This month / Last 30 days / Last 90 days)
 *   3. KPI strip (Spent / Income / Transactions / Avg per txn)
 *   4. Category donut
 *   5. Daily spend line
 *   6. Top merchants bar
 *   7. Day of week bar
 *   8. (Empty state) when the active window has no transactions
 */
@Composable
fun InsightsScreen(
    onOpenAgentic: () -> Unit = {},
    viewModel: InsightsViewModel = viewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMd),
    ) {
        Header(onOpenAgentic = onOpenAgentic)
        InsightsWindowSelector(
            selected = ui.window,
            onSelected = viewModel::setWindow,
        )
        val snapshot = ui.snapshot
        if (snapshot == null || snapshot.empty) {
            EmptyStateCard()
        } else {
            KpiStrip(
                spentLabel = stringResource(R.string.insights_kpi_spent),
                spentValue = InsightsFormat.compactAmount(snapshot.kpis.spentPaise, snapshot.currency),
                spentDelta = InsightsFormat.delta(snapshot.kpis.spentDeltaPct),
                incomeLabel = stringResource(R.string.insights_kpi_income),
                incomeValue = InsightsFormat.compactAmount(snapshot.kpis.incomePaise, snapshot.currency),
                incomeDelta = InsightsFormat.delta(snapshot.kpis.incomeDeltaPct),
                txnLabel = stringResource(R.string.insights_kpi_transactions),
                txnValue = snapshot.kpis.transactionCount.toString(),
                avgLabel = stringResource(R.string.insights_kpi_avg),
                avgValue = InsightsFormat.compactAmount(snapshot.kpis.avgPerTxnPaise, snapshot.currency),
            )
            CategoryDonutCard(snapshot = snapshot)
            DailyTrendCard(snapshot = snapshot, zone = zone)
            TopMerchantsCard(snapshot = snapshot)
            DayOfWeekCard(snapshot = snapshot)
        }
    }
}

@Composable
private fun Header(onOpenAgentic: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.insights_title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            CartoonIcon(
                id = R.drawable.art_sms_mascot,
                size = 56.dp,
            )
        }
        Text(
            text = stringResource(R.string.insights_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AskAiPill(onClick = onOpenAgentic)
    }
}

/**
 * "Ask AI" entry pill. Lives just below the header copy so
 * the user sees it without scrolling. Renders as a sticker
 * button: ink border, offset shadow, primary tint.
 */
@Composable
private fun AskAiPill(onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val shape = MaterialTheme.shapes.medium
    val shadow = Dimens.ShadowSmall
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .padding(end = shadow, bottom = shadow),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .matchParentSize()
                .offset(shadow, shadow)
                .background(outline, shape),
        )
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .background(primary, shape)
                .border(Dimens.BorderThin, outline, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm),
        ) {
            Text(
                text = stringResource(R.string.insights_ask_ai),
                style = MaterialTheme.typography.titleMedium,
                color = onPrimary,
            )
        }
    }
}

@Composable
private fun EmptyStateCard() {
    StickerCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SectionLabel(stringResource(R.string.insights_empty_title))
            Text(
                text = stringResource(R.string.insights_empty_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
