package com.spendai.app.domain.agent.insights

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.Account
import com.spendai.app.data.local.entity.FinancialSource
import com.spendai.app.data.local.entity.Merchant
import com.spendai.app.data.local.entity.MerchantMetadataKind
import com.spendai.app.data.local.entity.ParsedSms
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.RepromptJobStatus
import com.spendai.app.data.local.entity.SourceInstrumentType
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.local.entity.TransactionDirection
import com.spendai.app.data.local.entity.TransactionLinkType
import com.spendai.app.data.repository.MerchantRepository
import com.spendai.app.data.repository.RepromptJobRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-JVM (Robolectric) tests for the merchant mutation
 * tool. Locks down:
 *  - `setIsSelf = true` flips the flag, walks affected
 *    transactions, writes a SELF_TRANSFER link for visible
 *    transfer partners, and enqueues a reprompt job per
 *    affected row.
 *  - `addMetadata` upserts and rejects unknown kinds.
 *  - `removeMetadata` deletes the matching row.
 *  - Unknown merchant names return a result with `error`
 *    and do not touch any row.
 *  - The cap on reprompts per call (50) is honoured when
 *    a merchant has more than 50 affected transactions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = com.spendai.app.TestApp::class, sdk = [33])
class MerchantMutatorTest {

    private lateinit var db: AppDatabase
    private lateinit var merchantRepo: MerchantRepository
    private lateinit var repromptRepo: RepromptJobRepository
    private lateinit var mutator: MerchantMutator

    private val now = 1_700_000_000_000L
    private val DAY_MS = 24L * 60L * 60L * 1000L

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        merchantRepo = MerchantRepository(db.merchantDao(), db.merchantMetadataDao())
        repromptRepo = RepromptJobRepository(db.repromptJobDao())
        mutator = MerchantMutator(
            database = db,
            merchantRepository = merchantRepo,
            repromptJobRepository = repromptRepo,
            nowMillis = { now },
        )
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `setIsSelf flips the flag and enqueues one reprompt per affected txn`() = runBlocking {
        val (accountId, otherAccountId) = seedAccounts()
        val deepId = merchantRepo.insert(
            Merchant(name = "DEEP G", normalizedName = "deep g", firstSeenAt = 1L)
        )
        val txn = insertTxn(accountId, deepId, 5_000L, "DEBIT", now - 1L * DAY_MS)
        val partner = insertTxn(otherAccountId, null, 5_000L, "CREDIT", now - 1L * DAY_MS + 1L)

        val result = mutator.mutate(
            AgenticAction.MutateMerchant(
                thought = "user says Deep G is me",
                matchByName = "deep g",
                setIsSelf = true,
            )
        )

        assertNull(result.error)
        assertEquals(deepId, result.matchedMerchantId)
        assertTrue(result.isSelfChanged)
        assertTrue(result.isSelfNewValue)
        assertEquals(listOf(txn), result.affectedTransactionIds)
        // One self-link row from txn -> partner
        assertEquals(1, result.selfTransferLinksWritten)
        val links = db.transactionLinkDao().getAllOnce()
        assertEquals(1, links.size)
        assertEquals(TransactionLinkType.SELF_TRANSFER.name, links[0].linkType)
        assertEquals(partner, links[0].toTransactionId)
        // One reprompt job
        assertEquals(1, result.repromptsEnqueued)
        val jobs = repromptRepo.getStale(0L)
        assertEquals(1, jobs.size)
        assertEquals(RepromptJobStatus.PENDING.name, jobs[0].status)
        assertEquals(txn, jobs[0].transactionId)
    }

    @Test
    fun `setIsSelf does not link transactions without a visible partner`() = runBlocking {
        val accountId = seedAccounts().first
        val deepId = merchantRepo.insert(
            Merchant(name = "DEEP G", normalizedName = "deep g", firstSeenAt = 1L)
        )
        val txn = insertTxn(accountId, deepId, 5_000L, "DEBIT", now - 1L * DAY_MS)
        // No opposite-direction partner in the same window.

        val result = mutator.mutate(
            AgenticAction.MutateMerchant(
                thought = "user",
                matchByName = "deep g",
                setIsSelf = true,
            )
        )

        assertTrue(result.isSelfChanged)
        assertEquals(1, result.affectedTransactionIds.size)
        assertEquals(0, result.selfTransferLinksWritten)
        // Reprompt is still enqueued so A3 sees the new context
        assertEquals(1, result.repromptsEnqueued)
    }

    @Test
    fun `addMetadata upserts and rejects unknown kinds`() = runBlocking {
        val id = merchantRepo.insert(
            Merchant(name = "MOHAN KUSHWANA", normalizedName = "mohan kushwana", firstSeenAt = 1L)
        )
        val ok = mutator.mutate(
            AgenticAction.MutateMerchant(
                thought = "user",
                matchByName = "mohan kushwana",
                addMetadata = listOf(AgenticAction.MetadataOp("NOTE", "pani puri vendor")),
            )
        )
        assertNull(ok.error)
        assertEquals(1, ok.metadataAdded.size)
        assertEquals("NOTE", ok.metadataAdded[0].kind)
        assertEquals("pani puri vendor", ok.metadataAdded[0].value)
        assertEquals(
            "pani puri vendor",
            merchantRepo.getMetadata(id).single().value,
        )
        // Re-save: replaces the value, keeps createdAt
        val second = mutator.mutate(
            AgenticAction.MutateMerchant(
                thought = "user",
                matchByName = "mohan kushwana",
                addMetadata = listOf(AgenticAction.MetadataOp("NOTE", "pani puri wala")),
            )
        )
        assertNull(second.error)
        assertEquals(1, merchantRepo.getMetadata(id).size)
        assertEquals("pani puri wala", merchantRepo.getMetadata(id).single().value)

        // Unknown kind -> error, no mutation applied
        val bad = mutator.mutate(
            AgenticAction.MutateMerchant(
                thought = "user",
                matchByName = "mohan kushwana",
                addMetadata = listOf(AgenticAction.MetadataOp("WAT", "x")),
            )
        )
        assertNotNull(bad.error)
        // Original row is intact
        assertEquals(1, merchantRepo.getMetadata(id).size)
        assertEquals("pani puri wala", merchantRepo.getMetadata(id).single().value)
    }

    @Test
    fun `removeMetadata deletes only the matching kind`() = runBlocking {
        val id = merchantRepo.insert(
            Merchant(name = "X", normalizedName = "x", firstSeenAt = 1L)
        )
        merchantRepo.putMetadata(id, MerchantMetadataKind.NOTE, "n", now = 1L)
        merchantRepo.putMetadata(id, MerchantMetadataKind.LABEL, "l", now = 2L)
        val result = mutator.mutate(
            AgenticAction.MutateMerchant(
                thought = "user",
                matchByName = "x",
                removeMetadata = listOf("NOTE"),
            )
        )
        assertNull(result.error)
        assertEquals(listOf("NOTE"), result.metadataRemoved)
        assertEquals(1, merchantRepo.getMetadata(id).size)
        assertEquals("LABEL", merchantRepo.getMetadata(id).single().kind)
    }

    @Test
    fun `unknown merchant name returns an error without touching any row`() = runBlocking {
        merchantRepo.insert(
            Merchant(name = "A", normalizedName = "a", firstSeenAt = 1L)
        )
        val result = mutator.mutate(
            AgenticAction.MutateMerchant(
                thought = "user",
                matchByName = "no such merchant",
                setIsSelf = true,
            )
        )
        assertNotNull(result.error)
        // Original row is untouched
        val originalA = merchantRepo.getById(merchantRepo.findByNormalizedName("a")!!.id)!!
        assertFalse(originalA.isSelf)
    }

    @Test
    fun `setIsSelf caps the reprompt enqueue at the configured limit`() = runBlocking {
        val (accountId, otherAccountId) = seedAccounts()
        val deepId = merchantRepo.insert(
            Merchant(name = "DEEP G", normalizedName = "deep g", firstSeenAt = 1L)
        )
        // Insert 60 transactions on the self merchant; cap is 50.
        val ids = (1..60).map { i ->
            insertTxn(accountId, deepId, 100L, "DEBIT", now - (60L - i) * 60_000L)
        }
        // Provide a visible partner for every debit so the link step succeeds.
        ids.forEach { id ->
            insertTxn(otherAccountId, null, 100L, "CREDIT", db.transactionDao().getById(id)!!.txnAtMillis + 1L)
        }
        val result = mutator.mutate(
            AgenticAction.MutateMerchant(
                thought = "user",
                matchByName = "deep g",
                setIsSelf = true,
            )
        )
        assertNull(result.error)
        assertEquals(60, result.affectedTransactionIds.size)
        assertTrue(
            "reprompt enqueue should be capped, got ${result.repromptsEnqueued}",
            result.repromptsEnqueued <= MerchantMutator.MAX_AFFECTED_TXNS_FOR_REPROMPT,
        )
    }

    // --- helpers ---

    private suspend fun seedAccounts(): Pair<Long, Long> {
        val sourceId = db.financialSourceDao().upsert(
            FinancialSource(
                sourceKey = "HDFC", displayName = "HDFC", bankName = "HDFC",
                accountLast4 = "1234", deducedType = "BANK_SMS",
                firstSeenTimestamp = 1L,
            ),
        )
        val a = db.accountDao().insert(
            Account(
                sourceId = sourceId, instrumentType = SourceInstrumentType.ACCOUNT.name,
                issuer = "HDFC", maskedNumber = "XXXX1234", currency = "INR",
                holderName = "A", createdAt = 1L,
            ),
        )
        val b = db.accountDao().insert(
            Account(
                sourceId = sourceId, instrumentType = SourceInstrumentType.ACCOUNT.name,
                issuer = "ICICI", maskedNumber = "XXXX5678", currency = "INR",
                holderName = "B", createdAt = 2L,
            ),
        )
        return a to b
    }

    private suspend fun insertTxn(
        accountId: Long,
        merchantId: Long?,
        amountPaise: Long,
        direction: String,
        atMillis: Long,
    ): Long {
        val rawId = db.smsDao().insert(
            RawSmsMessage(
                senderAddress = "HDFC",
                msgBody = "fixture $atMillis",
                timestamp = atMillis,
            ),
        )
        val parsedId = db.parsedSmsDao().insert(
            ParsedSms(
                rawSmsId = rawId, parsedAt = now, kind = "TRANSACTION",
                amountPaise = amountPaise, currency = "INR", direction = direction,
                txnAtMillis = atMillis, a1Confidence = 1f, a1RawJson = "{}",
            ),
        )
        return db.transactionDao().insert(
            Transaction(
                accountId = accountId, merchantId = merchantId, rawSmsId = rawId,
                parsedSmsId = parsedId, amountPaise = amountPaise, currency = "INR",
                direction = direction, txnAtMillis = atMillis,
                status = "CONFIRMED", confidence = 1f, createdAt = now,
            )
        )
    }
}
