package com.spendai.app.domain.insights

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The time window the user picked for the insights view.
 *
 * The boundaries are computed in the caller's [ZoneId] so that
 * "This month" always means the user's local month, not UTC.
 * Each preset carries a [labelKey] used by the UI to look up
 * a string resource; the labels are intentionally not bundled
 * here so the domain layer stays free of Android resources.
 */
enum class InsightsWindow {
    THIS_MONTH,
    LAST_30_DAYS,
    LAST_90_DAYS,
}

/**
 * Closed-open `[start, end)` epoch-millis range. `end` is the
 * current instant at the time of the call; `start` is derived
 * from the preset. Both are inclusive of all transactions whose
 * `txnAtMillis` falls in `[start, end)`.
 */
data class InsightsRange(
    val start: Long,
    val end: Long,
) {
    val durationMillis: Long get() = (end - start).coerceAtLeast(0L)
}

object InsightsWindowCalculator {

    /**
     * Returns the `(start, end)` boundaries for [window] as of
     * [now] in [zone]. The boundaries are inclusive of [start]
     * and exclusive of [end] (the typical "since" range used by
     * the rest of the app's Room queries).
     */
    fun boundaries(
        window: InsightsWindow,
        now: Long,
        zone: ZoneId,
    ): InsightsRange {
        val nowInstant = Instant.ofEpochMilli(now)
        val end = now
        val start = when (window) {
            InsightsWindow.THIS_MONTH -> {
                val today: LocalDate = nowInstant.atZone(zone).toLocalDate()
                val ym = YearMonth.from(today)
                ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            }
            InsightsWindow.LAST_30_DAYS -> now - 30L * MILLIS_PER_DAY
            InsightsWindow.LAST_90_DAYS -> now - 90L * MILLIS_PER_DAY
        }
        return InsightsRange(start = start, end = end)
    }

    /**
     * Returns the immediately preceding equivalent range so the
     * UI can show "vs previous" deltas. For a 30-day window the
     * previous range is the 30 days before [current]; for a
     * month it's the calendar month before.
     */
    fun previousRange(
        current: InsightsRange,
        window: InsightsWindow,
        now: Long,
        zone: ZoneId,
    ): InsightsRange {
        val duration = current.durationMillis
        return when (window) {
            InsightsWindow.THIS_MONTH -> {
                val nowInstant = Instant.ofEpochMilli(now)
                val today = nowInstant.atZone(zone).toLocalDate()
                val currentMonth = YearMonth.from(today)
                val previousMonth = currentMonth.minusMonths(1)
                InsightsRange(
                    start = previousMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                    end = currentMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                )
            }
            InsightsWindow.LAST_30_DAYS,
            InsightsWindow.LAST_90_DAYS,
            -> InsightsRange(
                start = (current.start - duration).coerceAtLeast(0L),
                end = current.start,
            )
        }
    }

    private const val MILLIS_PER_DAY: Long = 24L * 60L * 60L * 1000L
}
