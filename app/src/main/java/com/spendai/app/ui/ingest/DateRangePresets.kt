package com.spendai.app.ui.ingest

import androidx.annotation.StringRes
import com.spendai.app.R
import com.spendai.app.domain.ingestion.DateRange

/**
 * The four quick-pick buttons in the date-range picker. Each preset
 * maps to a calendar-aligned [DateRange] half-open window.
 */
enum class DateRangePreset(@StringRes val labelRes: Int) {
    Today(R.string.ingest_preset_today),
    ThisWeek(R.string.ingest_preset_this_week),
    ThisMonth(R.string.ingest_preset_this_month),
    LastMonth(R.string.ingest_preset_last_month);

    fun toRange(now: Long = System.currentTimeMillis()): DateRange = when (this) {
        Today -> DateRange.today(now)
        ThisWeek -> DateRange.thisWeek(now)
        ThisMonth -> DateRange.thisMonth(now)
        LastMonth -> DateRange.lastMonth(now)
    }
}
