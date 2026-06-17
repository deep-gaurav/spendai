package com.spendai.app.domain.ingestion

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields

/**
 * A half-open time window `[startMillis, endMillis)`. The pipeline groups
 * messages inside a range by the local date the SMS arrived, so the
 * end-boundary is exclusive and a day is never double-counted at the
 * seam.
 *
 * Use [unbounded] for the WorkManager drain (process every pending row
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

        /**
         * "Today" — from 00:00 of the day containing [now] to [now].
         * Half-open, so messages received at exactly [now] are
         * included.
         */
        fun today(
            now: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): DateRange {
            val todayDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val startMillis = todayDate.atStartOfDay(zone).toInstant().toEpochMilli()
            return DateRange(startMillis, now)
        }

        /**
         * "This week" — from Monday 00:00 of the week containing [now]
         * to [now]. Pinned to [WeekFields.ISO] (Monday start) so the
         * range is stable across locales.
         */
        fun thisWeek(
            now: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): DateRange {
            val todayDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val monday = todayDate.with(WeekFields.ISO.firstDayOfWeek)
            val startMillis = monday.atStartOfDay(zone).toInstant().toEpochMilli()
            return DateRange(startMillis, now)
        }

        /**
         * "This month" — from 00:00 on the 1st of the month containing
         * [now] to [now]. Half-open, so messages received at exactly
         * [now] are included.
         */
        fun thisMonth(
            now: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): DateRange {
            val todayDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val firstOfMonth = todayDate.withDayOfMonth(1)
            val startMillis = firstOfMonth.atStartOfDay(zone).toInstant().toEpochMilli()
            return DateRange(startMillis, now)
        }

        /**
         * "Last month" — from 00:00 on the 1st of the month before the
         * one containing [now], to 00:00 on the 1st of the month
         * containing [now]. Half-open, so the boundary day is owned by
         * "this month".
         */
        fun lastMonth(
            now: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): DateRange {
            val todayDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val firstOfThisMonth = todayDate.withDayOfMonth(1)
            val firstOfLastMonth = firstOfThisMonth.minusMonths(1)
            val startMillis = firstOfLastMonth.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMillis = firstOfThisMonth.atStartOfDay(zone).toInstant().toEpochMilli()
            return DateRange(startMillis, endMillis)
        }

        /**
         * Custom range from a start day to an end day (inclusive on
         * both ends as local days). The end is exclusive at the
         * millisecond level: end-of-day on [endDayLocal] minus 1 ms,
         * so any message received before midnight of the end day is
         * included. Half-open `[startDayLocal 00:00, endDayLocal 23:59:59.999]`
         * converted to a half-open `[start, end+1ms)` would be
         * equivalent.
         */
        fun dayRange(
            startDayLocal: LocalDate,
            endDayLocal: LocalDate,
            zone: ZoneId = ZoneId.systemDefault(),
        ): DateRange {
            require(!startDayLocal.isAfter(endDayLocal)) {
                "startDayLocal ($startDayLocal) must not be after endDayLocal ($endDayLocal)"
            }
            val startMillis = startDayLocal.atStartOfDay(zone).toInstant().toEpochMilli()
            // End is the start of the day AFTER endDayLocal — half-open.
            val endMillis = endDayLocal.plusDays(1)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            return DateRange(startMillis, endMillis)
        }
    }
}
