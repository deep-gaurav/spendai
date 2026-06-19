package com.spendai.app.ui.ingest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.HorizontalDivider
import com.spendai.app.R
import com.spendai.app.domain.ingestion.DateRange
import com.spendai.app.ui.theme.Dimens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Full-screen sheet for picking an SMS ingest date range. Hosts a row of
 * preset chips, the Material 3 [DateRangePicker], and a live preview of
 * the selected range, with Cancel / Apply anchored in the top bar.
 *
 * UX:
 *  - Defaults: last 7 days (`[today - 6, today]`), so the picker opens
 *    on a useful range.
 *  - Presets: Today, This week, This month, Last month. Tapping a chip
 *    writes the new range into the picker state and the calendar
 *    scrolls to it.
 *  - "Apply" emits the current selection; "Cancel" emits nothing.
 *  - The custom range is treated as `[startDay, endDay]` inclusive on
 *    both ends as local days (half-open at the millisecond level, so
 *    any message received before midnight of the end day is included).
 *
 * The calendar uses `weight(1f, fill = false)` to shrink to the
 * remaining space; its internal LazyColumn scrolls the months, so the
 * whole dialog fits on small phones without clipping the action bar.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun IngestRangePickerDialog(
    onConfirm: (DateRange) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val now = remember { System.currentTimeMillis() }
    val today = remember { Instant.ofEpochMilli(now).atZone(zone).toLocalDate() }
    val defaultStart = remember { today.minusDays(6) }
    val defaultEnd = today

    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = defaultStart
            .atStartOfDay(zone).toInstant().toEpochMilli(),
        initialSelectedEndDateMillis = defaultEnd
            .atStartOfDay(zone).toInstant().toEpochMilli(),
        initialDisplayMode = DisplayMode.Picker,
    )

    var activePreset by remember { mutableStateOf<DateRangePreset?>(null) }

    LaunchedEffect(state.selectedStartDateMillis, state.selectedEndDateMillis) {
        val s = state.selectedStartDateMillis
        val e = state.selectedEndDateMillis
        if (s != null && e != null) {
            activePreset = matchPreset(s, e, now, zone)
        } else {
            activePreset = null
        }
    }

    val applyEnabled = state.selectedStartDateMillis != null

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.ingest_picker_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.ingest_picker_cancel))
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val s = state.selectedStartDateMillis ?: return@TextButton
                            val e = state.selectedEndDateMillis ?: s
                            val startDay = Instant.ofEpochMilli(s).atZone(zone).toLocalDate()
                            val endDay = Instant.ofEpochMilli(e).atZone(zone).toLocalDate()
                            onConfirm(DateRange.dayRange(startDay, endDay, zone))
                        },
                        enabled = applyEnabled,
                    ) {
                        Text(stringResource(R.string.ingest_picker_apply))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .padding(horizontal = Dimens.SpaceMd, vertical = Dimens.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSm),
        ) {
            Text(
                text = stringResource(R.string.ingest_picker_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
            ) {
                DateRangePreset.values().forEach { preset ->
                    FilterChip(
                        selected = activePreset == preset,
                        onClick = {
                            activePreset = preset
                            val range = preset.toRange(now)
                            val endAdjusted = (range.endMillis - 1L).coerceAtLeast(range.startMillis)
                            state.setSelection(range.startMillis, endAdjusted)
                        },
                        label = { Text(stringResource(preset.labelRes)) },
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            // Calendar shrinks to remaining space; the picker's own
            // internal LazyColumn scrolls the months, so the whole
            // dialog fits on small phones without clipping the bar.
            DateRangePicker(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(vertical = Dimens.SpaceXs),
                showModeToggle = true,
            )

            val preview = remember(
                state.selectedStartDateMillis,
                state.selectedEndDateMillis,
            ) {
                val s = state.selectedStartDateMillis
                val e = state.selectedEndDateMillis
                if (s != null && e != null) {
                    formatRange(s, e, zone)
                } else null
            }
            if (preview != null) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Text(
                    text = stringResource(R.string.ingest_picker_no_selection),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun matchPreset(
    startMillis: Long,
    endMillis: Long,
    now: Long,
    zone: ZoneId,
): DateRangePreset? = DateRangePreset.values().firstOrNull { preset ->
    val r = preset.toRange(now)
    val sMatch = kotlin.math.abs(r.startMillis - startMillis) < 60_000L
    val eMatch = kotlin.math.abs(r.endMillis - endMillis) < 24 * 3_600_000L + 60_000L
    sMatch && eMatch
}

private fun formatRange(
    startMillis: Long,
    endMillis: Long,
    zone: ZoneId,
): String {
    val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val start = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
    val end = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDate()
    val startStr = start.format(fmt)
    val endStr = end.format(fmt)
    val days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1
    return "$startStr \u2192 $endStr  \u00b7  $days day${if (days == 1L) "" else "s"}"
}
