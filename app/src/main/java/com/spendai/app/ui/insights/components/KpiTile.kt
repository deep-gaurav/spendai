package com.spendai.app.ui.insights.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spendai.app.R
import com.spendai.app.ui.insights.format.DeltaFormat
import com.spendai.app.ui.insights.format.InsightsFormat
import com.spendai.app.ui.theme.Dimens

/**
 * A single KPI tile — label, big value, optional delta
 * indicator under the value. The tile is a "mini sticker":
 * same ink border + offset shadow as [com.spendai.app.ui.components.StickerCard]
 * but smaller, with no halftone (the halftone would compete
 * with the bold numeric value).
 */
@Composable
fun KpiTile(
    label: String,
    value: String,
    delta: DeltaFormat,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outline
    val shape = RoundedCornerShape(12.dp)
    val shadow = Dimens.ShadowSmall
    val (deltaText, deltaColor) = deltaTextAndColor(delta)

    Box(
        modifier = modifier
            .padding(end = shadow, bottom = shadow),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(shadow, shadow)
                .background(outline, shape),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, shape)
                .border(Dimens.BorderThin, outline, shape)
                .padding(horizontal = Dimens.SpaceSm, vertical = Dimens.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (deltaText != null) {
                Text(
                    text = deltaText,
                    style = MaterialTheme.typography.labelSmall,
                    color = deltaColor,
                )
            }
        }
    }
}

/**
 * The 2x2 KPI strip rendered at the top of the Insights
 * screen. Order is fixed: Spent, Income, Transactions, Avg.
 */
@Composable
fun KpiStrip(
    spentLabel: String,
    spentValue: String,
    spentDelta: DeltaFormat,
    incomeLabel: String,
    incomeValue: String,
    incomeDelta: DeltaFormat,
    txnLabel: String,
    txnValue: String,
    avgLabel: String,
    avgValue: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
        ) {
            KpiTile(
                label = spentLabel,
                value = spentValue,
                delta = spentDelta,
                modifier = Modifier.weight(1f),
            )
            KpiTile(
                label = incomeLabel,
                value = incomeValue,
                delta = incomeDelta,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
        ) {
            KpiTile(
                label = txnLabel,
                value = txnValue,
                delta = DeltaFormat.Flat,
                modifier = Modifier.weight(1f),
            )
            KpiTile(
                label = avgLabel,
                value = avgValue,
                delta = DeltaFormat.Flat,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun deltaTextAndColor(delta: DeltaFormat): Pair<String?, Color> = when (delta) {
    is DeltaFormat.Up -> {
        val txt = stringResource(R.string.insights_kpi_delta_up, delta.percentText)
        txt to MaterialTheme.colorScheme.error
    }
    is DeltaFormat.Down -> {
        val txt = stringResource(R.string.insights_kpi_delta_down, delta.percentText)
        txt to MaterialTheme.colorScheme.tertiary
    }
    DeltaFormat.Flat -> "—" to MaterialTheme.colorScheme.onSurfaceVariant
    DeltaFormat.NoComparison -> stringResource(R.string.insights_kpi_no_comparison) to
        MaterialTheme.colorScheme.onSurfaceVariant
}

@Suppress("UNUSED_PARAMETER")
private fun InsightsFormat_unused(): Unit = Unit
