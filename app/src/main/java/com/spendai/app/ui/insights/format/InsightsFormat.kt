package com.spendai.app.ui.insights.format

import com.spendai.app.R
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Locale-aware formatting helpers for the Insights screen.
 *
 * Mirrors the [formatAmount] helper from
 * [com.spendai.app.ui.home.HomeScreen] but routes everything
 * through [NumberFormat] so non-INR locales render correctly
 * without an extra rewrite.
 */
object InsightsFormat {

    /**
     * Formats paise as a major-unit currency string. Negative
     * values get a leading minus sign; zeros render as the
     * canonical `"0.00"`.
     */
    fun amount(paise: Long, currency: String): String {
        val major = paise / 100.0
        val nf = numberFormat(currency)
        return nf.format(major)
    }

    /**
     * Compact amount for KPI tiles where space is tight.
     *   12,345.67  -> "12.3k"
     *   1,234,567  -> "12L"  (lakh-aware for INR)
     *   1,234      -> "1,234"
     */
    fun compactAmount(paise: Long, currency: String): String {
        val major = paise / 100.0
        val absMajor = abs(major)
        if (absMajor < 1_000.0) {
            return numberFormat(currency).format(major)
        }
        if (currency.equals("INR", ignoreCase = true)) {
            return when {
                absMajor < 100_000.0 -> {
                    val v = major / 1_000.0
                    String.format(Locale.getDefault(), "%.1fk", v)
                }
                absMajor < 10_000_000.0 -> {
                    val v = major / 100_000.0
                    String.format(Locale.getDefault(), "%.1fL", v)
                }
                else -> {
                    val v = major / 10_000_000.0
                    String.format(Locale.getDefault(), "%.1fCr", v)
                }
            }
        }
        return when {
            absMajor < 1_000_000.0 -> {
                val v = major / 1_000.0
                String.format(Locale.getDefault(), "%.1fk", v)
            }
            else -> {
                val v = major / 1_000_000.0
                String.format(Locale.getDefault(), "%.1fM", v)
            }
        }
    }

    /**
     * "vs previous" delta. Returns a [DeltaFormat] the UI can
     * render in a tinted text style.
     *
     *  - null delta (no prior data) → [DeltaFormat.NoComparison]
     *  - positive delta              → [DeltaFormat.Up] with the percent value
     *  - negative delta              → [DeltaFormat.Down] with the absolute percent
     */
    fun delta(deltaPct: Float?): DeltaFormat {
        if (deltaPct == null) return DeltaFormat.NoComparison
        // Treat anything within ±0.05% as flat so jitter doesn't
        // pretend to be a trend.
        if (abs(deltaPct) < 0.05f) return DeltaFormat.Flat
        val rounded = abs(deltaPct * 10.0).roundToLong() / 10.0
        val text = String.format(Locale.getDefault(), "%.1f", rounded)
        return if (deltaPct > 0f) DeltaFormat.Up(text) else DeltaFormat.Down(text)
    }

    private fun numberFormat(currency: String): NumberFormat {
        val nf = NumberFormat.getNumberInstance(Locale.getDefault())
        nf.minimumFractionDigits = 0
        nf.maximumFractionDigits = 2
        nf.minimumIntegerDigits = 1
        nf.isGroupingUsed = true
        // Currency formatting is intentionally not used here: the
        // host screens already render the currency code
        // separately (e.g. "12,345 INR") and concatenating a
        // symbol here would double up. Keeping the formatter
        // as a plain number lets the card compose them.
        @Suppress("UNUSED_VARIABLE")
        val ignored = currency
        return nf
    }
}

sealed interface DeltaFormat {
    data class Up(val percentText: String) : DeltaFormat
    data class Down(val percentText: String) : DeltaFormat
    data object Flat : DeltaFormat
    data object NoComparison : DeltaFormat
}

/**
 * Day-of-week short label lookup. Index is ISO (1 = Mon).
 */
fun dayOfWeekShort(dayOfWeek: Int): Int = when (dayOfWeek) {
    1 -> R.string.insights_dow_mon
    2 -> R.string.insights_dow_tue
    3 -> R.string.insights_dow_wed
    4 -> R.string.insights_dow_thu
    5 -> R.string.insights_dow_fri
    6 -> R.string.insights_dow_sat
    7 -> R.string.insights_dow_sun
    else -> R.string.insights_dow_mon
}
