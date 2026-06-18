package com.spendai.app.ui.insights.format

import com.spendai.app.ui.insights.format.DeltaFormat
import com.spendai.app.ui.insights.format.InsightsFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class InsightsFormatTest {

    @Test
    fun `amount formats paise as a major-unit number`() {
        val s = InsightsFormat.amount(123_456L, "INR")
        // Locale-dependent grouping, so just assert structure.
        assertTrue("expected '.' in fractional: $s", s.contains("."))
        assertTrue("expected '1,234' whole: $s", s.contains("1,234"))
        assertTrue("expected '56' fractional: $s", s.endsWith("56"))
    }

    @Test
    fun `compactAmount uses L suffix for INR above 100k`() {
        val s = InsightsFormat.compactAmount(150_000_00L, "INR") // 150,000 rupees
        // 1.5L with one decimal place
        assertEquals(String.format(Locale.getDefault(), "%.1fL", 1.5), s)
    }

    @Test
    fun `compactAmount uses k suffix below 100k INR`() {
        val s = InsightsFormat.compactAmount(12_345_00L, "INR") // 12,345 rupees
        // 12345 / 1000 = 12.345, formatted with one decimal => "12.3k"
        assertEquals("12.3k", s)
        assertTrue("expected 'k' suffix: $s", s.endsWith("k"))
    }

    @Test
    fun `compactAmount uses M for non-INR above 1M`() {
        val s = InsightsFormat.compactAmount(1_500_000_00L, "USD") // 1,500,000 USD
        assertTrue("expected 'M' suffix: $s", s.endsWith("M"))
    }

    @Test
    fun `delta returns NoComparison for null`() {
        assertEquals(DeltaFormat.NoComparison, InsightsFormat.delta(null))
    }

    @Test
    fun `delta returns Flat for tiny deltas`() {
        assertEquals(DeltaFormat.Flat, InsightsFormat.delta(0.04f))
        assertEquals(DeltaFormat.Flat, InsightsFormat.delta(-0.04f))
    }

    @Test
    fun `delta returns Up for positive percentages`() {
        val d = InsightsFormat.delta(12.34f) as DeltaFormat.Up
        assertEquals("12.3", d.percentText)
    }

    @Test
    fun `delta returns Down with absolute value for negative percentages`() {
        val d = InsightsFormat.delta(-7.5f) as DeltaFormat.Down
        assertEquals("7.5", d.percentText)
    }
}
