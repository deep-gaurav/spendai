package com.spendai.app.ui.insights.charts

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class LineChartTest {

    @Test
    fun `densePoints fills missing days with zero`() {
        val first = LocalDate.of(2026, 6, 1)
        val last = LocalDate.of(2026, 6, 5)
        val sparse = listOf(
            LinePoint(first, 100L),
            LinePoint(LocalDate.of(2026, 6, 3), 200L),
            LinePoint(last, 50L),
        )
        val dense = densePoints(sparse, first, last)
        assertEquals(5, dense.size)
        assertEquals(100L, dense[0].value)
        assertEquals(0L, dense[1].value)
        assertEquals(200L, dense[2].value)
        assertEquals(0L, dense[3].value)
        assertEquals(50L, dense[4].value)
        assertEquals(listOf(1, 2, 3, 4, 5), dense.map { it.date.dayOfMonth })
    }

    @Test
    fun `densePoints returns single entry when first equals last`() {
        val date = LocalDate.of(2026, 6, 1)
        val dense = densePoints(listOf(LinePoint(date, 42L)), date, date)
        assertEquals(1, dense.size)
        assertEquals(42L, dense[0].value)
    }

    @Test
    fun `densePoints returns all-zeros when input is empty`() {
        val first = LocalDate.of(2026, 6, 1)
        val last = LocalDate.of(2026, 6, 2)
        val dense = densePoints(emptyList(), first, last)
        assertEquals(2, dense.size)
        assertEquals(0L, dense[0].value)
        assertEquals(0L, dense[1].value)
    }
}
