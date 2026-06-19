package com.spendai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.local.entity.TransactionLinkType
import com.spendai.app.data.local.entity.TransactionDirection
import kotlinx.coroutines.flow.Flow

/**
 * Read-only aggregate DAO that powers the Insights screen.
 *
 * All methods take `(startMillis, endMillis)` as a half-open
 * range (`[start, end)`) and filter on the indexed
 * `txnAtMillis` column so the queries stay cheap even with
 * tens of thousands of rows. Every aggregate is grouped by
 * `currency` so a future multi-currency device renders the
 * dominant currency without an extra rewrite — the UI side
 * picks the top-currency row from the result.
 *
 * ## Self-exclusion
 *
 * Every query also drops transactions that are self-events,
 * defined as either:
 *
 *  - The transaction participates in a `transaction_link` row
 *    with `linkType = 'SELF_TRANSFER'` (user-initiated move
 *    between two of their own accounts, e.g. card to wallet
 *    top-up).
 *  - The transaction's merchant is marked `isSelf = 1` in the
 *    `merchant` table (counterparty is the user themself —
 *    their own name in a UPI handle, their own card
 *    nickname, etc.).
 *
 * Both forms drop the row from every spend / income / category
 * / merchant aggregate. The exclusion pattern is the only
 * source of truth for "ignore in insights only"; the agentic
 * Ask-AI SQL gateway's schema block mirrors the same predicate
 * so the model's queries stay consistent.
 *
 * The queries return `Flow` so the screen auto-refreshes the
 * moment a new ingestion run lands more transactions in
 * `spend_transaction`. No polling, no manual reload.
 */
@Dao
interface InsightsDao {

    /**
     * One row per currency, per direction, in the active range.
     * Empty rows are omitted — i.e. a window with no credits
     * has no `CREDIT` row at all. Self-events (transfers and
     * `isSelf` merchants) are excluded.
     */
    @Query(
        """
        SELECT direction AS direction,
               currency AS currency,
               COUNT(*) AS txnCount,
               COALESCE(SUM(amountPaise), 0) AS totalPaise
        FROM spend_transaction
        WHERE txnAtMillis >= :startMillis
          AND txnAtMillis < :endMillis
          AND NOT EXISTS (
              SELECT 1 FROM transaction_link l
              WHERE l.linkType = :selfTransfer
                AND (l.fromTransactionId = spend_transaction.id
                     OR l.toTransactionId = spend_transaction.id)
          )
          AND (merchantId IS NULL OR merchantId NOT IN (
              SELECT id FROM merchant WHERE isSelf = 1
          ))
        GROUP BY direction, currency
        """
    )
    fun observeKpiRows(
        startMillis: Long,
        endMillis: Long,
        selfTransfer: String = TransactionLinkType.SELF_TRANSFER.name,
    ): Flow<List<KpiRow>>

    /**
     * One row per category (or one row with `categoryId IS NULL`
     * for uncategorised transactions) for DEBIT spend only.
     * Ordered by `SUM(amountPaise) DESC` so the donut chart can
     * group the tail into an "Other" slice client-side. Self-
     * events are excluded so they don't bloat the donut.
     */
    @Query(
        """
        SELECT t.categoryId AS categoryId,
               COALESCE(c.name, 'Uncategorised') AS categoryName,
               COALESCE(c.emoji, '') AS categoryEmoji,
               t.currency AS currency,
               COUNT(*) AS txnCount,
               COALESCE(SUM(t.amountPaise), 0) AS totalPaise
        FROM spend_transaction t
        LEFT JOIN category c ON t.categoryId = c.id
        WHERE t.direction = :debit
          AND t.txnAtMillis >= :startMillis
          AND t.txnAtMillis < :endMillis
          AND NOT EXISTS (
              SELECT 1 FROM transaction_link l
              WHERE l.linkType = :selfTransfer
                AND (l.fromTransactionId = t.id
                     OR l.toTransactionId = t.id)
          )
          AND (t.merchantId IS NULL OR t.merchantId NOT IN (
              SELECT id FROM merchant WHERE isSelf = 1
          ))
        GROUP BY t.categoryId, t.currency
        ORDER BY totalPaise DESC
        """
    )
    fun observeCategoryBreakdown(
        startMillis: Long,
        endMillis: Long,
        debit: String = TransactionDirection.DEBIT.name,
        selfTransfer: String = TransactionLinkType.SELF_TRANSFER.name,
    ): Flow<List<CategoryBreakdownRow>>

    /**
     * Top-N merchants by DEBIT spend, in the active range.
     * `currency` is part of the GROUP BY so a multi-currency
     * device stays correct; the UI picks the dominant currency
     * before rendering. Self-events are excluded.
     */
    @Query(
        """
        SELECT m.id AS merchantId,
               m.name AS merchantName,
               COALESCE(c.emoji, '') AS categoryEmoji,
               t.currency AS currency,
               COUNT(*) AS txnCount,
               COALESCE(SUM(t.amountPaise), 0) AS totalPaise
        FROM spend_transaction t
        JOIN merchant m ON t.merchantId = m.id
        LEFT JOIN category c ON m.categoryId = c.id
        WHERE t.direction = :debit
          AND t.txnAtMillis >= :startMillis
          AND t.txnAtMillis < :endMillis
          AND NOT EXISTS (
              SELECT 1 FROM transaction_link l
              WHERE l.linkType = :selfTransfer
                AND (l.fromTransactionId = t.id
                     OR l.toTransactionId = t.id)
          )
          AND m.isSelf = 0
        GROUP BY m.id, t.currency
        ORDER BY totalPaise DESC
        LIMIT :limit
        """
    )
    fun observeTopMerchants(
        startMillis: Long,
        endMillis: Long,
        limit: Int,
        debit: String = TransactionDirection.DEBIT.name,
        selfTransfer: String = TransactionLinkType.SELF_TRANSFER.name,
    ): Flow<List<MerchantBreakdownRow>>

    /**
     * Raw transactions in the active range, ordered by time
     * ascending. The caller buckets in Kotlin by LocalDate and
     * by DayOfWeek — SQLite date functions are not worth the
     * portability cost and Kotlin is trivial here.
     *
     * Self-events are excluded so the daily / day-of-week
     * series reflects real spend, not internal moves.
     */
    @Query(
        """
        SELECT *
        FROM spend_transaction
        WHERE direction = :direction
          AND txnAtMillis >= :startMillis
          AND txnAtMillis < :endMillis
          AND NOT EXISTS (
              SELECT 1 FROM transaction_link l
              WHERE l.linkType = :selfTransfer
                AND (l.fromTransactionId = spend_transaction.id
                     OR l.toTransactionId = spend_transaction.id)
          )
          AND (merchantId IS NULL OR merchantId NOT IN (
              SELECT id FROM merchant WHERE isSelf = 1
          ))
        ORDER BY txnAtMillis ASC
        """
    )
    fun observeTransactionsInRange(
        startMillis: Long,
        endMillis: Long,
        direction: String,
        selfTransfer: String = TransactionLinkType.SELF_TRANSFER.name,
    ): Flow<List<Transaction>>
}

/**
 * Row projection for [InsightsDao.observeKpiRows]. Currency is
 * part of the key because a future multi-currency device needs
 * per-currency totals.
 */
data class KpiRow(
    val direction: String,
    val currency: String,
    val txnCount: Int,
    val totalPaise: Long,
)

/**
 * Row projection for [InsightsDao.observeCategoryBreakdown].
 * `categoryId` is null for transactions without a category.
 */
data class CategoryBreakdownRow(
    val categoryId: Long?,
    val categoryName: String,
    val categoryEmoji: String,
    val currency: String,
    val txnCount: Int,
    val totalPaise: Long,
)

/**
 * Row projection for [InsightsDao.observeTopMerchants].
 */
data class MerchantBreakdownRow(
    val merchantId: Long,
    val merchantName: String,
    val categoryEmoji: String,
    val currency: String,
    val txnCount: Int,
    val totalPaise: Long,
)
