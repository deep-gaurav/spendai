package com.spendai.app.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class InsightsWindowTest {

    private val ist = ZoneId.of("Asia/Kolkata")
    private val utc = ZoneId.of("UTC")

    @Test
    fun `THIS_MONTH boundaries span the first-of-month midnight to now`() {
        val now = LocalDateTime.of(2026, 6, 18, 14, 30)
            .atZone(ist).toInstant().toEpochMilli()
        val range = InsightsWindowCalculator.boundaries(InsightsWindow.THIS_MONTH, now, ist)
        val expectedStart = LocalDate.of(2026, 6, 1)
            .atStartOfDay(ist).toInstant().toEpochMilli()
        assertEquals(expectedStart, range.start)
        assertEquals(now, range.end)
    }

    @Test
    fun `LAST_30_DAYS boundaries are now-30d to now`() {
        val now = 1_000_000_000_000L
        val range = InsightsWindowCalculator.boundaries(InsightsWindow.LAST_30_DAYS, now, utc)
        assertEquals(now - 30L * 24L * 60L * 60L * 1000L, range.start)
        assertEquals(now, range.end)
    }

    @Test
    fun `LAST_90_DAYS boundaries are now-90d to now`() {
        val now = 1_000_000_000_000L
        val range = InsightsWindowCalculator.boundaries(InsightsWindow.LAST_90_DAYS, now, utc)
        assertEquals(now - 90L * 24L * 60L * 60L * 1000L, range.start)
        assertEquals(now, range.end)
    }

    @Test
    fun `previous range for THIS_MONTH is the prior calendar month`() {
        val now = LocalDateTime.of(2026, 6, 18, 14, 30)
            .atZone(ist).toInstant().toEpochMilli()
        val current = InsightsWindowCalculator.boundaries(InsightsWindow.THIS_MONTH, now, ist)
        val previous = InsightsWindowCalculator.previousRange(current, InsightsWindow.THIS_MONTH, now, ist)
        val expectedStart = LocalDate.of(2026, 5, 1).atStartOfDay(ist).toInstant().toEpochMilli()
        val expectedEnd = LocalDate.of(2026, 6, 1).atStartOfDay(ist).toInstant().toEpochMilli()
        assertEquals(expectedStart, previous.start)
        assertEquals(expectedEnd, previous.end)
    }

    @Test
    fun `previous range for LAST_30_DAYS is the prior 30-day window`() {
        val now = 1_000_000_000_000L
        val current = InsightsWindowCalculator.boundaries(InsightsWindow.LAST_30_DAYS, now, utc)
        val previous = InsightsWindowCalculator.previousRange(current, InsightsWindow.LAST_30_DAYS, now, utc)
        assertEquals(current.start - (current.end - current.start), previous.start)
        assertEquals(current.start, previous.end)
    }

    @Test
    fun `previous range for LAST_90_DAYS is the prior 90-day window`() {
        val now = 1_000_000_000_000L
        val current = InsightsWindowCalculator.boundaries(InsightsWindow.LAST_90_DAYS, now, utc)
        val previous = InsightsWindowCalculator.previousRange(current, InsightsWindow.LAST_90_DAYS, now, utc)
        assertTrue(previous.start < current.start)
        assertEquals(current.start, previous.end)
    }
}
