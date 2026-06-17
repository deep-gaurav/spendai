package com.spendai.app.domain.ingestion

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A half-open time window `[startMillis, endMillis)`. The pipeline groups
 * messages inside a range by the local date the SMS arrived, so the
 * end-boundary is exclusive and a day is never double-counted at the
 * seam.
 *
 * Use [unbounded] for the WorkManager drain (process every UNPARSED row
 * regardless of when it arrived).
 */
data class DateRange(
    val startMillis: Long,
    val endMillis: Long,
) {
    init {
        require(startMillis <= endMillis) {
            "startMillis ($startMillis) must be <= endMillis ($endMillis)"
        }
    }

    fun contains(epochMillis: Long): Boolean =
        epochMillis >= startMillis && epochMillis < endMillis

    fun toLocalDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    companion object {
        fun unbounded(): DateRange = DateRange(0L, Long.MAX_VALUE)

        /**
         * Last 24 × N hours ending at [endMillis]. Days are derived by
         * the caller's local zone, not by 24-hour blocks — so a 7-day
         * range that crosses a DST transition still contains 7 calendar
         * days. Use [calendarDaysBack] for strict calendar semantics.
         */
        fun lastHoursBack(endMillis: Long, hours: Int): DateRange =
            DateRange(endMillis - hours * 3_600_000L, endMillis)

        /**
         * Strict calendar-aligned range: starts at the start of the day
         * `daysBack` days before the day containing [endMillis], ends
         * at the start of the day containing [endMillis]. So
         * `calendarDaysBack(now, 7)` covers yesterday + the 6 days
         * before it, ending at 00:00 today.
         */
        fun calendarDaysBack(
            endMillis: Long,
            daysBack: Int,
            zone: ZoneId = ZoneId.systemDefault(),
        ): DateRange {
            val endDay = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDate()
            val endDayStart = endDay.atStartOfDay(zone).toInstant().toEpochMilli()
            val startDay = endDay.minusDays(daysBack.toLong())
            val startDayStart = startDay.atStartOfDay(zone).toInstant().toEpochMilli()
            return DateRange(startDayStart, endDayStart)
        }
    }
}
