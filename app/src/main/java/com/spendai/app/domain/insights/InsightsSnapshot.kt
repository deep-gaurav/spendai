package com.spendai.app.domain.insights

import java.time.LocalDate

/**
 * The full snapshot the Insights screen renders for one window.
 *
 * Built by [com.spendai.app.data.repository.InsightsRepository] from
 * the Room aggregates; consumed by
 * [com.spendai.app.ui.insights.InsightsViewModel] and the
 * individual card composables. All amounts are paise-as-Long;
 * rendering layers do the locale-aware formatting.
 *
 * `empty` is `true` when the window contains no transactions at
 * all; the UI uses it to switch into the empty-state path
 * instead of rendering a sea of flat-line charts.
 */
data class InsightsSnapshot(
    val window: InsightsWindow,
    val range: InsightsRange,
    val previousRange: InsightsRange,
    val currency: String,
    val kpis: KpiSummary,
    val categoryBreakdown: List<CategoryBucket>,
    val topMerchants: List<MerchantBucket>,
    val dailySeries: List<DailyBucket>,
    val dayOfWeekSeries: List<DowBucket>,
    val empty: Boolean,
)

/**
 * Top-of-screen KPI strip. All amounts are paise.
 *
 * `spentDeltaPct` / `incomeDeltaPct` are signed percentages
 * comparing the current window to the previous one:
 *   - `+12.5`  → spent 12.5% more than previous
 *   - `-3.0`   → spent 3.0% less than previous
 *   - `null`   → previous period had no spend/income to compare to
 */
data class KpiSummary(
    val spentPaise: Long,
    val incomePaise: Long,
    val transactionCount: Int,
    val avgPerTxnPaise: Long,
    val spentDeltaPct: Float?,
    val incomeDeltaPct: Float?,
    val currency: String,
)

/**
 * One slice of the category donut. `name` is the user-facing
 * category label (or "Uncategorised" for transactions whose
 * `categoryId` is null). `paise` is the sum of `amountPaise`
 * for DEBIT rows in the active window.
 */
data class CategoryBucket(
    val categoryId: Long?,
    val name: String,
    val emoji: String,
    val paise: Long,
    val txnCount: Int,
)

/**
 * One row in the "Top merchants" bar. Always DEBIT spend.
 */
data class MerchantBucket(
    val merchantId: Long,
    val name: String,
    val emoji: String,
    val paise: Long,
    val txnCount: Int,
)

/**
 * One bar of the daily-spend line chart. `paise` is the sum of
 * DEBIT rows whose `txnAtMillis` falls on [date] in the active
 * window. The series is sparse — only days with at least one
 * debit appear — so the chart layer fills gaps with zero.
 */
data class DailyBucket(
    val date: LocalDate,
    val paise: Long,
)

/**
 * One bar of the day-of-week chart. The series always contains
 * exactly 7 entries (Mon..Sun) in a fixed order, even if some
 * days have zero spend. `paise` is the SUM of all DEBIT rows
 * on that weekday across the entire window (the chart layer
 * may further average by occurrence count — see InsightsUiState).
 */
data class DowBucket(
    val dayOfWeek: Int, // 1 = Monday … 7 = Sunday (DayOfWeek ISO)
    val paise: Long,
)
