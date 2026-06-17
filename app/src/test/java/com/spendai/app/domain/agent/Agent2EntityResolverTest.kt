package com.spendai.app.domain.agent

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.Account
import com.spendai.app.data.local.entity.Merchant
import com.spendai.app.data.local.entity.ParsedSms
import com.spendai.app.data.local.entity.ParsedSmsKind
import com.spendai.app.data.repository.AccountRepository
import com.spendai.app.data.repository.CategoryRepository
import com.spendai.app.data.repository.FinancialSourceRepository
import com.spendai.app.data.repository.MerchantRepository
import com.spendai.app.data.repository.SmsRepository
import com.spendai.app.data.repository.ParsedSmsRepository
import com.spendai.app.data.repository.TransactionRepository
import com.spendai.app.domain.model.MerchantNormalizer
import com.spendai.app.inference.GemmaInferenceEngine
import com.spendai.app.inference.InferenceState
import com.spendai.app.TestApp
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the per-message A2 commit path.
 *
 * The 64K context window is enforced by capping the prompt bundle
 * (100 merchants, 20 sources, 50 accounts). These tests lock down
 * that cap and the dedup behaviour on the resolver's materialise
 * step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, sdk = [33])
class Agent2EntityResolverTest {

    private lateinit var db: AppDatabase
    private lateinit var resolver: Agent2EntityResolver
    private lateinit var merchantRepo: MerchantRepository
    private lateinit var txnRepo: TransactionRepository
    private val engine: GemmaInferenceEngine = mockk(relaxed = true)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val sourceRepo = FinancialSourceRepository(db.financialSourceDao())
        val accountRepo = AccountRepository(db.accountDao())
        val categoryRepo = CategoryRepository(db.categoryDao())
        merchantRepo = MerchantRepository(db.merchantDao())
        txnRepo = TransactionRepository(db.transactionDao())
        val smsRepo = SmsRepository(db.smsDao())
        coEvery { engine.state } returns MutableStateFlow(InferenceState.Ready("NPU"))
        resolver = Agent2EntityResolver(
            engine = engine,
            database = db,
            sourceRepository = sourceRepo,
            accountRepository = accountRepo,
            merchantRepository = merchantRepo,
            transactionRepository = txnRepo,
            categoryRepository = categoryRepo,
        )
        val now = System.currentTimeMillis()
        rawSmsId = kotlinx.coroutines.runBlocking {
            SmsRepository(db.smsDao()).insert(
                com.spendai.app.data.local.entity.RawSmsMessage(
                    senderAddress = "VK-TEST",
                    msgBody = "Rs 500 at Acme",
                    timestamp = now,
                    status = com.spendai.app.data.local.entity.SmsStatus.PARSED,
                )
            )
        }
        parsedSmsId = kotlinx.coroutines.runBlocking {
            ParsedSmsRepository(db.parsedSmsDao()).insert(
                ParsedSms(
                    rawSmsId = rawSmsId,
                    parsedAt = now,
                    kind = ParsedSmsKind.TRANSACTION.name,
                )
            )
        }
    }

    @After
    fun tearDown() { db.close() }

    private var rawSmsId: Long = 0L
    private var parsedSmsId: Long = 0L

    private fun parsedSms(
        amountPaise: Long = 50000L,
        direction: String = "DEBIT",
        merchantRaw: String = "Acme",
    ): ParsedSms = ParsedSms(
        id = parsedSmsId,
        rawSmsId = rawSmsId,
        parsedAt = 0L,
        kind = ParsedSmsKind.TRANSACTION.name,
        amountPaise = amountPaise,
        currency = "INR",
        direction = direction,
        channel = "UPI",
        merchantRaw = merchantRaw,
        a1RawJson = "{}",
    )

    private val a2ContractAllNew = """{
        "source":  {"kind":"new","sourceKey":"VK-TEST","deducedType":"UPI","suggestedBankName":null,"suggestedInstrumentType":"UNKNOWN","suggestedDisplayName":null,"confidence":0.9},
        "account": {"kind":"new","instrumentType":"ACCOUNT","issuer":"Bank","maskedNumber":"XXXX1234","currency":"INR","confidence":0.9},
        "merchant":{"kind":"new","name":"Acme","normalizedName":"acme","vpa":null,"confidence":0.9},
        "a2Confidence":0.9
    }""".trimIndent()

    @Test
    fun `prompt bundle contains at most 100 merchants`() = runTest {
        // Seed 150 merchants. The model will be invoked with a
        // prompt bundle; capture it and assert the bundle lists 100
        // (the cap). We don't care about the commit outcome here
        // because the model returns all-new (the dedup against the
        // 100-row bundle is best-effort; full 150 are in the DB).
        val now = System.currentTimeMillis()
        repeat(150) { i ->
            merchantRepo.insert(
                Merchant(
                    name = "M$i",
                    normalizedName = "m$i",
                    firstSeenAt = now - i * 1000L,
                )
            )
        }
        val promptSlot = slot<String>()
        coEvery {
            engine.generatePredictionTracking(capture(promptSlot), any<String>(), anyNullable())
        } returns flowOf(a2ContractAllNew)

        resolver.resolveAndCommit(parsedSms(), smsTimestampMillis = 1_700_000_000_000L)

        coVerify(exactly = 1) {
            engine.generatePredictionTracking(any<String>(), any<String>(), anyNullable())
        }
        val prompt = promptSlot.captured
        // Count merchant entries in the JSON bundle. We can scan for
        // `"id":<n>` patterns under the knownMerchants section; the
        // simplest check is to count unique normalized names in the
        // prompt and assert == 100.
        val merchantSection = prompt.substringAfter("\"knownMerchants\":[")
            .substringBefore("]}")
        val nameOccurrences = Regex("\"name\":\"M(\\d+)\"").findAll(merchantSection).toList()
        assertEquals("bundle should contain exactly 100 merchants", 100, nameOccurrences.size)
        // The most-recently-seen are M0..M99 (descending firstSeenAt).
        val seen = nameOccurrences.map { it.groupValues[1].toInt() }.toSet()
        assertTrue("expected M0..M99 present, missing: ${(0..99).toSet() - seen}", (0..99).toSet() == seen)
    }

    @Test
    fun `A2 existing-merchant response links to the existing row`() = runTest {
        // Pre-seed a merchant that the model will point at.
        val existingId = merchantRepo.insert(
            Merchant(name = "Acme", normalizedName = "acme", firstSeenAt = 0L)
        )
        val a2Resp = """{
            "source":  {"kind":"new","sourceKey":"VK-TEST","deducedType":"UPI","suggestedInstrumentType":"UNKNOWN","confidence":0.9},
            "account": {"kind":"new","instrumentType":"ACCOUNT","issuer":"Bank","maskedNumber":"XXXX1234","currency":"INR","confidence":0.9},
            "merchant":{"kind":"existing","merchantId":$existingId,"confidence":0.9},
            "a2Confidence":0.9
        }""".trimIndent()
        coEvery { engine.generatePredictionTracking(any<String>(), any<String>(), anyNullable()) } returns flowOf(a2Resp)

        val outcome = resolver.resolveAndCommit(parsedSms(), smsTimestampMillis = 1_700_000_000_000L)
        val txnId = outcome.transactionId
        val txn = txnRepo.getById(txnId)
        assertNotNull(txn)
        assertEquals(existingId, txn!!.merchantId)
        // No new merchant row was created.
        assertEquals(1, db.merchantDao().getAllOnce().size)
    }

    @Test
    fun `A2 new-merchant response that collides on normalizedName dedupes to existing`() = runTest {
        // Pre-seed a merchant with the normalized name the model
        // is about to propose.
        val existingId = merchantRepo.insert(
            Merchant(name = "ACME COFFEE", normalizedName = "acme", firstSeenAt = 0L)
        )
        val a2Resp = """{
            "source":  {"kind":"new","sourceKey":"VK-TEST","deducedType":"UPI","suggestedInstrumentType":"UNKNOWN","confidence":0.9},
            "account": {"kind":"new","instrumentType":"ACCOUNT","issuer":"Bank","maskedNumber":"XXXX1234","currency":"INR","confidence":0.9},
            "merchant":{"kind":"new","name":"Acme Coffee Bar","normalizedName":"acme","vpa":null,"confidence":0.9},
            "a2Confidence":0.9
        }""".trimIndent()
        coEvery { engine.generatePredictionTracking(any<String>(), any<String>(), anyNullable()) } returns flowOf(a2Resp)

        val outcome = resolver.resolveAndCommit(parsedSms(), smsTimestampMillis = 1_700_000_000_000L)
        val txnId = outcome.transactionId
        val txn = txnRepo.getById(txnId)
        assertNotNull(txn)
        // The transaction was linked to the existing merchant, not
        // a freshly inserted one. The dedup check uses
        // MerchantNormalizer.normalize + findByNormalizedName, but
        // the contract also passes the model-supplied normalizedName
        // as a fallback when normalize() returns empty.
        assertEquals(existingId, txn!!.merchantId)
        assertEquals(1, db.merchantDao().getAllOnce().size)
    }

    @Test
    fun `a2Confidence is preserved on the committed transaction`() = runTest {
        val a2Resp = """{
            "source":  {"kind":"new","sourceKey":"VK-TEST","deducedType":"UPI","suggestedInstrumentType":"UNKNOWN","confidence":0.3},
            "account": {"kind":"new","instrumentType":"ACCOUNT","issuer":"Bank","maskedNumber":"XXXX1234","currency":"INR","confidence":0.3},
            "merchant":{"kind":"none","confidence":0.3},
            "a2Confidence":0.42
        }""".trimIndent()
        coEvery { engine.generatePredictionTracking(any<String>(), any<String>(), anyNullable()) } returns flowOf(a2Resp)

        val outcome = resolver.resolveAndCommit(parsedSms(), smsTimestampMillis = 1_700_000_000_000L)
        val txnId = outcome.transactionId
        val txn = txnRepo.getById(txnId)
        assertNotNull(txn)
        // Even though confidence is low, the row is committed (no
        // review queue) and the value is preserved for the edit UI.
        assertEquals(0.42f, txn!!.confidence, 0.0001f)
    }

    @Test
    fun `a2Confidence out of range throws and the row is not committed`() = runTest {
        val a2Resp = """{
            "source":  {"kind":"new","sourceKey":"VK-TEST","deducedType":"UPI","suggestedInstrumentType":"UNKNOWN","confidence":0.9},
            "account": {"kind":"new","instrumentType":"ACCOUNT","issuer":"Bank","maskedNumber":"XXXX1234","currency":"INR","confidence":0.9},
            "merchant":{"kind":"none","confidence":0.9},
            "a2Confidence":2.5
        }""".trimIndent()
        coEvery { engine.generatePredictionTracking(any<String>(), any<String>(), anyNullable()) } returns flowOf(a2Resp)

        try {
            resolver.resolveAndCommit(parsedSms(), smsTimestampMillis = 1_700_000_000_000L)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        // No transaction was committed.
        assertEquals(0, txnRepo.getSince(0L).size)
    }
}
