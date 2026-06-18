package com.spendai.app.ui.insights.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spendai.app.R
import com.spendai.app.domain.insights.InsightsWindow
import com.spendai.app.ui.theme.Dimens

/**
 * Segmented "This month / Last 30 days / Last 90 days" picker
 * at the top of the Insights screen. Built inline rather than
 * via M3 SegmentedButton so the chunky ink border + offset
 * shadow matches the rest of the app's cartoon aesthetic.
 *
 * Each segment claims an equal share of the row width via
 * [RowScope.weight].
 */
@Composable
fun InsightsWindowSelector(
    selected: InsightsWindow,
    onSelected: (InsightsWindow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outline
    val selectedFill = MaterialTheme.colorScheme.primary
    val unselectedFill = MaterialTheme.colorScheme.surface
    val selectedContent = MaterialTheme.colorScheme.onPrimary
    val unselectedContent = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(12.dp)
    val shadow = 2.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(end = shadow, bottom = shadow),
    ) {
        // Offset shadow under the whole strip.
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(shadow, shadow)
                .background(outline, shape),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(unselectedFill)
                .border(Dimens.BorderThick, outline, shape)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InsightsWindow.entries.forEach { window ->
                SegmentChip(
                    label = stringResource(window.labelRes()),
                    selected = window == selected,
                    selectedFill = selectedFill,
                    unselectedFill = unselectedFill,
                    selectedContent = selectedContent,
                    unselectedContent = unselectedContent,
                    onClick = { onSelected(window) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RowScope.SegmentChip(
    label: String,
    selected: Boolean,
    selectedFill: Color,
    unselectedFill: Color,
    selectedContent: Color,
    unselectedContent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 40.dp)
            .clickable(onClick = onClick)
            .background(
                color = if (selected) selectedFill else unselectedFill,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = Dimens.SpaceSm, vertical = Dimens.SpaceXs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) selectedContent else unselectedContent,
        )
    }
}

/** Maps [InsightsWindow] to a localized label resource. */
fun InsightsWindow.labelRes(): Int = when (this) {
    InsightsWindow.THIS_MONTH -> R.string.insights_window_this_month
    InsightsWindow.LAST_30_DAYS -> R.string.insights_window_last_30
    InsightsWindow.LAST_90_DAYS -> R.string.insights_window_last_90
}
