package com.spendai.app.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.Account
import com.spendai.app.data.local.entity.Category
import com.spendai.app.data.local.entity.FinancialSource
import com.spendai.app.data.local.entity.Merchant
import com.spendai.app.data.local.entity.ParsedSms
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SourceInstrumentType
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.local.entity.TransactionDirection
import com.spendai.app.data.local.entity.TransactionLink
import com.spendai.app.data.local.entity.TransactionLinkType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-backed Room DAO test for [InsightsDao].
 *
 * Fixture seeds:
 *   - 1 FinancialSource / Account
 *   - 2 Categories (Food, Transport)
 *   - 2 Merchants (Zomato @ Food, Uber @ Transport)
 *   - 4 Transactions across a 10-day span: 3 debits, 1 credit
 *   - 1 SELF_TRANSFER link that pins the two big rows together
 *     (200_000 debit at 9d-ago + 200_000 credit at 9d-ago). The
 *     exclusion filter must drop both from every aggregate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [33])
class InsightsDaoTest {

    private lateinit var db: AppDatabase

    private val now = 1_700_000_000_000L // 2023-11-14T22:13:20Z

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        runBlocking { seed() }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed() {
        // Source / Account
        val sourceId = db.financialSourceDao().upsert(
            FinancialSource(
                sourceKey = "HDFC",
                displayName = "HDFC Bank",
                bankName = "HDFC",
                accountLast4 = "1234",
                deducedType = "BANK_SMS",
                firstSeenTimestamp = 1L,
            ),
        )
        val accountId = db.accountDao().insert(
            Account(
                sourceId = sourceId,
                instrumentType = SourceInstrumentType.ACCOUNT.name,
                issuer = "HDFC",
                maskedNumber = "XXXX1234",
                currency = "INR",
                holderName = "Test",
                createdAt = 1L,
            ),
        )
        // Categories
        val foodCatId = db.categoryDao().insert(
            Category(name = "Food", normalizedName = "food", emoji = "\uD83C\uDF74", createdAt = 1L),
        )
        val transportCatId = db.categoryDao().insert(
            Category(name = "Transport", normalizedName = "transport", emoji = "\uD83D\uDE95", createdAt = 2L),
        )
        // Merchants
        val zomatoId = db.merchantDao().insertIgnore(
            Merchant(
                name = "Zomato",
                normalizedName = "zomato",
                categoryId = foodCatId,
                firstSeenAt = 1L,
            ),
        )
        val uberId = db.merchantDao().insertIgnore(
            Merchant(
                name = "Uber",
                normalizedName = "uber",
                categoryId = transportCatId,
                firstSeenAt = 2L,
            ),
        )

        // Transactions: 3 debits, 1 credit, plus 1 self-transfer pair.
        // The self-transfer pair dominates the totals when included,
        // so the assertions below verify the filter strips it.
        val entries = listOf(
            // Real debits / credit (not in any link)
            TxnFixture(accountId, zomatoId, foodCatId, 25_000L, "DEBIT", now - 9L * DAY_MS),
            TxnFixture(accountId, uberId, transportCatId, 10_000L, "DEBIT", now - 6L * DAY_MS),
            TxnFixture(accountId, zomatoId, foodCatId, 30_000L, "DEBIT", now - 3L * DAY_MS),
            TxnFixture(accountId, null, null, 50_000L, "CREDIT", now - 1L * DAY_MS),
            // Self-transfer pair: 2L debit + 2L credit on the same day
            TxnFixture(accountId, zomatoId, foodCatId, 200_000L, "DEBIT", now - 2L * DAY_MS - 1L),
            TxnFixture(accountId, zomatoId, foodCatId, 200_000L, "CREDIT", now - 2L * DAY_MS + 1L),
        )
        val txnIds = mutableListOf<Long>()
        for (entry in entries) {
            val rawId = db.smsDao().insert(entry.rawMessage())
            val parsedId = db.parsedSmsDao().insert(entry.parsedSms(rawId))
            val txnId = db.transactionDao().insert(entry.transaction(rawId, parsedId))
            txnIds += txnId
        }
        // Wire the self-transfer link between the two big rows (last two).
        db.transactionLinkDao().insertIgnore(
            TransactionLink(
                fromTransactionId = txnIds[4],
                toTransactionId = txnIds[5],
                linkType = TransactionLinkType.SELF_TRANSFER.name,
                confidence = 1f,
                createdAt = now,
            ),
        )
    }

    private data class TxnFixture(
        val accountId: Long,
        val merchantId: Long?,
        val categoryId: Long?,
        val magnitude: Long,
        val direction: String,
        val atMillis: Long,
    ) {
        fun rawMessage() = RawSmsMessage(
            senderAddress = "HDFC",
            msgBody = "fixture $atMillis",
            timestamp = atMillis,
        )

        fun parsedSms(rawId: Long) = ParsedSms(
            rawSmsId = rawId,
            parsedAt = atMillis,
            kind = "TRANSACTION",
            amountPaise = if (direction == "DEBIT") magnitude else magnitude,
            currency = "INR",
            direction = direction,
            txnAtMillis = atMillis,
            a1Confidence = 1f,
            a1RawJson = "{}",
        )

        fun transaction(rawId: Long, parsedId: Long) = Transaction(
            accountId = accountId,
            merchantId = merchantId,
            rawSmsId = rawId,
            parsedSmsId = parsedId,
            amountPaise = magnitude,
            currency = "INR",
            direction = direction,
            txnAtMillis = atMillis,
            status = "CONFIRMED",
            confidence = 1f,
            categoryId = categoryId,
            createdAt = atMillis,
        )
    }

    @Test
    fun `kpi rows aggregate by direction and currency in range`() = runTest {
        val rows = db.insightsDao().observeKpiRows(now - 10L * DAY_MS, now + 1L).first()
        val debit = rows.first { it.direction == "DEBIT" }
        val credit = rows.first { it.direction == "CREDIT" }
        // Without the self-transfer pair: 3 real debits (25k + 10k + 30k = 65k)
        // and 1 real credit (50k). The 2L self-transfer rows are excluded.
        assertEquals(3, debit.txnCount)
        assertEquals(65_000L, debit.totalPaise)
        assertEquals(1, credit.txnCount)
        assertEquals(50_000L, credit.totalPaise)
    }

    @Test
    fun `category breakdown groups by category and is ordered by total desc`() = runTest {
        val rows = db.insightsDao().observeCategoryBreakdown(now - 10L * DAY_MS, now + 1L).first()
        assertEquals(2, rows.size)
        assertEquals("Food", rows[0].categoryName)
        assertEquals("Transport", rows[1].categoryName)
        // Food real spend: 25k + 30k = 55k. The 2L self-transfer @ Food is excluded.
        assertEquals(55_000L, rows[0].totalPaise)
        assertEquals(10_000L, rows[1].totalPaise)
    }

    @Test
    fun `top merchants respects limit and orders by total desc`() = runTest {
        val rows = db.insightsDao().observeTopMerchants(now - 10L * DAY_MS, now + 1L, limit = 5).first()
        assertEquals(2, rows.size)
        assertEquals("Zomato", rows[0].merchantName)
        // 25k + 30k = 55k, self-transfer excluded
        assertEquals(55_000L, rows[0].totalPaise)
        assertEquals("Uber", rows[1].merchantName)
    }

    @Test
    fun `transactions in range returns only DEBIT rows in the window`() = runTest {
        val txns = db.insightsDao().observeTransactionsInRange(
            now - 10L * DAY_MS, now + 1L, TransactionDirection.DEBIT.name,
        ).first()
        // 3 real debits, the 2L self-transfer @ -2d is excluded
        assertEquals(3, txns.size)
        assertTrue(txns.all { it.direction == "DEBIT" })
    }

    @Test
    fun `range filter excludes out-of-window rows`() = runTest {
        val txns = db.insightsDao().observeTransactionsInRange(
            now - 5L * DAY_MS, now + 1L, TransactionDirection.DEBIT.name,
        ).first()
        // Range is half-open [now-5d, now); the -6d Uber row falls outside,
        // the -3d Zomato row is the only DEBIT in [-5d, now).
        // The -2d self-transfer @ 2L is excluded by the SELF_TRANSFER filter.
        assertEquals(1, txns.size)
    }

    @Test
    fun `self-transfer rows are excluded from every aggregate`() = runTest {
        val kpiRows = db.insightsDao().observeKpiRows(now - 10L * DAY_MS, now + 1L).first()
        val catRows = db.insightsDao().observeCategoryBreakdown(now - 10L * DAY_MS, now + 1L).first()
        val merchantRows = db.insightsDao().observeTopMerchants(now - 10L * DAY_MS, now + 1L, limit = 10).first()
        val txnRows = db.insightsDao().observeTransactionsInRange(
            now - 10L * DAY_MS, now + 1L, TransactionDirection.DEBIT.name,
        ).first()
        val creditRows = db.insightsDao().observeTransactionsInRange(
            now - 10L * DAY_MS, now + 1L, TransactionDirection.CREDIT.name,
        ).first()

        // The 2L self-transfer pair (at -2d) is invisible to every view.
        kpiRows.forEach { row ->
            assertTrue(
                "kpi row should not include the 2L self-transfer",
                row.totalPaise < 100_000L,
            )
        }
        catRows.forEach { row ->
            assertTrue(
                "category row should not include the 2L self-transfer",
                row.totalPaise < 100_000L,
            )
        }
        merchantRows.forEach { row ->
            assertTrue(
                "merchant row should not include the 2L self-transfer",
                row.totalPaise < 100_000L,
            )
        }
        assertFalse(
            "no returned DEBIT should be the 2L self-transfer",
            txnRows.any { it.amountPaise == 200_000L },
        )
        assertFalse(
            "no returned CREDIT should be the 2L self-transfer",
            creditRows.any { it.amountPaise == 200_000L },
        )
    }

    companion object {
        private const val DAY_MS: Long = 24L * 60L * 60L * 1000L
    }
}
