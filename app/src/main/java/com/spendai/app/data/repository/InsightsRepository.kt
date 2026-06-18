package com.spendai.app.data.repository

import com.spendai.app.data.local.dao.CategoryBreakdownRow
import com.spendai.app.data.local.dao.InsightsDao
import com.spendai.app.data.local.dao.KpiRow
import com.spendai.app.data.local.dao.MerchantBreakdownRow
import com.spendai.app.data.local.entity.TransactionDirection
import com.spendai.app.domain.insights.CategoryBucket
import com.spendai.app.domain.insights.DailyBucket
import com.spendai.app.domain.insights.DowBucket
import com.spendai.app.domain.insights.InsightsRange
import com.spendai.app.domain.insights.InsightsSnapshot
import com.spendai.app.domain.insights.InsightsWindow
import com.spendai.app.domain.insights.InsightsWindowCalculator
import com.spendai.app.domain.insights.KpiSummary
import com.spendai.app.domain.insights.MerchantBucket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Read-only repository for the Insights screen. Combines the
 * four aggregate queries into a single [InsightsSnapshot]
 * flow that the ViewModel can render directly.
 *
 * The "dominant currency" is the currency of the DEBIT row
 * with the largest `SUM(amountPaise)`; ties are broken by the
 * currency code alphabetically. CREDIT totals are reported
 * in the KPI strip regardless of the dominant currency
 * (using the dominant currency's value if there is no
 * matching credit row).
 */
class InsightsRepository(private val dao: InsightsDao) {

    fun snapshot(
        window: InsightsWindow,
        now: Long,
        zone: ZoneId,
        topMerchantsLimit: Int = DEFAULT_TOP_MERCHANTS,
    ): Flow<InsightsSnapshot> {
        val range = InsightsWindowCalculator.boundaries(window, now, zone)
        val previous = InsightsWindowCalculator.previousRange(range, window, now, zone)

        val kpiCurrent = dao.observeKpiRows(range.start, range.end)
        val kpiPrevious = dao.observeKpiRows(previous.start, previous.end)
        val categories = dao.observeCategoryBreakdown(range.start, range.end)
        val topMerchants = dao.observeTopMerchants(range.start, range.end, topMerchantsLimit)
        val debitTxns = dao.observeTransactionsInRange(
            startMillis = range.start,
            endMillis = range.end,
            direction = TransactionDirection.DEBIT.name,
        )

        return combine(
            kpiCurrent,
            kpiPrevious,
            categories,
            topMerchants,
            debitTxns,
        ) { currentRows, previousRows, categoryRows, merchantRows, debitTxns ->
            assemble(
                window = window,
                range = range,
                previous = previous,
                zone = zone,
                currentKpiRows = currentRows,
                previousKpiRows = previousRows,
                categoryRows = categoryRows,
                merchantRows = merchantRows,
                debitTxns = debitTxns,
            )
        }
    }

    private fun assemble(
        window: InsightsWindow,
        range: InsightsRange,
        previous: InsightsRange,
        zone: ZoneId,
        currentKpiRows: List<KpiRow>,
        previousKpiRows: List<KpiRow>,
        categoryRows: List<CategoryBreakdownRow>,
        merchantRows: List<MerchantBreakdownRow>,
        debitTxns: List<com.spendai.app.data.local.entity.Transaction>,
    ): InsightsSnapshot {
        val dominantCurrency = dominantDebitCurrency(currentKpiRows) ?: "INR"
        val kpis = buildKpis(currentKpiRows, previousKpiRows, dominantCurrency)
        val cats = buildCategoryBuckets(categoryRows, dominantCurrency)
        val merchants = buildMerchantBuckets(merchantRows, dominantCurrency)
        val daily = buildDailySeries(debitTxns, range, zone)
        val dow = buildDowSeries(debitTxns, zone)
        val txnCount = currentKpiRows.sumOf { it.txnCount }
        return InsightsSnapshot(
            window = window,
            range = range,
            previousRange = previous,
            currency = dominantCurrency,
            kpis = kpis,
            categoryBreakdown = cats,
            topMerchants = merchants,
            dailySeries = daily,
            dayOfWeekSeries = dow,
            empty = txnCount == 0,
        )
    }

    private fun dominantDebitCurrency(rows: List<KpiRow>): String? =
        rows.asSequence()
            .filter { it.direction == TransactionDirection.DEBIT.name }
            .maxWithOrNull(
                compareBy({ it.totalPaise }, { it.currency })
            )
            ?.currency

    private fun buildKpis(
        current: List<KpiRow>,
        previous: List<KpiRow>,
        dominantCurrency: String,
    ): KpiSummary {
        val currentDebit = current.firstOrNull {
            it.direction == TransactionDirection.DEBIT.name && it.currency == dominantCurrency
        }
        val currentCredit = current.firstOrNull {
            it.direction == TransactionDirection.CREDIT.name && it.currency == dominantCurrency
        }
        val prevDebit = previous.firstOrNull {
            it.direction == TransactionDirection.DEBIT.name && it.currency == dominantCurrency
        }
        val prevCredit = previous.firstOrNull {
            it.direction == TransactionDirection.CREDIT.name && it.currency == dominantCurrency
        }
        val spent = currentDebit?.totalPaise ?: 0L
        val income = currentCredit?.totalPaise ?: 0L
        val txnCount = currentDebit?.txnCount ?: 0
        val avg = if (txnCount > 0) spent / txnCount else 0L
        return KpiSummary(
            spentPaise = spent,
            incomePaise = income,
            transactionCount = txnCount,
            avgPerTxnPaise = avg,
            spentDeltaPct = deltaPct(spent, prevDebit?.totalPaise ?: 0L),
            incomeDeltaPct = deltaPct(income, prevCredit?.totalPaise ?: 0L),
            currency = dominantCurrency,
        )
    }

    private fun deltaPct(current: Long, previous: Long): Float? {
        if (previous == 0L) return null
        return ((current - previous).toDouble() / previous.toDouble() * 100.0).toFloat()
    }

    private fun buildCategoryBuckets(
        rows: List<CategoryBreakdownRow>,
        dominantCurrency: String,
    ): List<CategoryBucket> = rows.asSequence()
        .filter { it.currency == dominantCurrency }
        .map { row ->
            CategoryBucket(
                categoryId = row.categoryId,
                name = row.categoryName,
                emoji = row.categoryEmoji,
                paise = row.totalPaise,
                txnCount = row.txnCount,
            )
        }
        .toList()

    private fun buildMerchantBuckets(
        rows: List<MerchantBreakdownRow>,
        dominantCurrency: String,
    ): List<MerchantBucket> = rows.asSequence()
        .filter { it.currency == dominantCurrency }
        .map { row ->
            MerchantBucket(
                merchantId = row.merchantId,
                name = row.merchantName,
                emoji = row.categoryEmoji,
                paise = row.totalPaise,
                txnCount = row.txnCount,
            )
        }
        .toList()

    private fun buildDailySeries(
        txns: List<com.spendai.app.data.local.entity.Transaction>,
        range: InsightsRange,
        zone: ZoneId,
    ): List<DailyBucket> {
        if (txns.isEmpty()) return emptyList()
        val grouped = txns.groupBy {
            Instant.ofEpochMilli(it.txnAtMillis).atZone(zone).toLocalDate()
        }
        return grouped.entries
            .sortedBy { it.key }
            .map { (date, list) ->
                DailyBucket(date = date, paise = list.sumOf { it.amountPaise })
            }
    }

    private fun buildDowSeries(
        txns: List<com.spendai.app.data.local.entity.Transaction>,
        zone: ZoneId,
    ): List<DowBucket> {
        // Always 7 entries (Mon..Sun) in ISO order so the bar
        // chart can render a stable x-axis even when a day has
        // zero spend.
        val byDow = IntArray(8) // index 1..7
        txns.forEach { txn ->
            val dow = Instant.ofEpochMilli(txn.txnAtMillis)
                .atZone(zone)
                .dayOfWeek
                .value
            byDow[dow] += txn.amountPaise.toInt().coerceAtLeast(0)
        }
        return (1..7).map { DowBucket(dayOfWeek = it, paise = byDow[it].toLong()) }
    }

    companion object {
        const val DEFAULT_TOP_MERCHANTS: Int = 5

        /** Empty snapshot factory used by previews and tests. */
        fun emptySnapshot(
            window: InsightsWindow,
            now: Long,
            zone: ZoneId,
        ): InsightsSnapshot {
            val range = InsightsWindowCalculator.boundaries(window, now, zone)
            val previous = InsightsWindowCalculator.previousRange(range, window, now, zone)
            return InsightsSnapshot(
                window = window,
                range = range,
                previousRange = previous,
                currency = "INR",
                kpis = KpiSummary(
                    spentPaise = 0L,
                    incomePaise = 0L,
                    transactionCount = 0,
                    avgPerTxnPaise = 0L,
                    spentDeltaPct = null,
                    incomeDeltaPct = null,
                    currency = "INR",
                ),
                categoryBreakdown = emptyList(),
                topMerchants = emptyList(),
                dailySeries = emptyList(),
                dayOfWeekSeries = (1..7).map { DowBucket(it, 0L) },
                empty = true,
            )
        }
    }
}
