package com.spendai.app.domain.ingestion

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SmsStatus
import com.spendai.app.data.repository.AccountRepository
import com.spendai.app.data.repository.CategoryRepository
import com.spendai.app.data.repository.FinancialSourceRepository
import com.spendai.app.data.repository.IngestionLogRepository
import com.spendai.app.data.repository.MerchantRepository
import com.spendai.app.data.repository.ParsedSmsRepository
import com.spendai.app.data.repository.SmsRepository
import com.spendai.app.data.repository.TransactionRepository
import com.spendai.app.domain.agent.Agent1SmsParser
import com.spendai.app.domain.agent.Agent2EntityResolver
import com.spendai.app.domain.agent.Agent3Auditor
import com.spendai.app.domain.ingestion.sources.ListSmsSource
import com.spendai.app.inference.GemmaInferenceEngine
import com.spendai.app.inference.InferenceState
import com.spendai.app.TestApp
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks down the v6 idempotency contract:
 *
 *  - On a successful commit, `raw_sms.processedAt` is set and
 *    `raw_sms.status = PARSED`.
 *  - On an A1 IGNORE, `raw_sms.processedAt` is set and
 *    `raw_sms.status = IGNORED`.
 *  - On an A1 or A2 error, `processedAt` stays null, `status`
 *    stays UNPARSED, and `lastError` captures the reason.
 *  - A row whose `processedAt` is set is excluded by
 *    [SmsRepository.pendingInRange] / [SmsRepository.pendingOnce]
 *    on the next run — so a re-run over the same range is a
 *    no-op for finished work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, sdk = [33])
class IngestionPipelineIdempotencyTest {

    private lateinit var db: AppDatabase
    private lateinit var pipeline: IngestionPipeline
    private lateinit var smsRepo: SmsRepository
    private val engine: GemmaInferenceEngine = mockk(relaxed = true)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        smsRepo = SmsRepository(db.smsDao())
        val parsedRepo = ParsedSmsRepository(db.parsedSmsDao())
        val sourceRepo = FinancialSourceRepository(db.financialSourceDao())
        val accountRepo = AccountRepository(db.accountDao())
        val categoryRepo = CategoryRepository(db.categoryDao())
        val merchantRepo = MerchantRepository(db.merchantDao())
        val txnRepo = TransactionRepository(db.transactionDao())
        val ingestionLogRepo = IngestionLogRepository(db.ingestionLogDao())
        coEvery { engine.state } returns MutableStateFlow(InferenceState.Ready("NPU"))
        val a1 = Agent1SmsParser(engine, parsedRepo)
        val a2 = Agent2EntityResolver(
            engine = engine,
            database = db,
            sourceRepository = sourceRepo,
            accountRepository = accountRepo,
            merchantRepository = merchantRepo,
            transactionRepository = txnRepo,
            categoryRepository = categoryRepo,
        )
        val a3 = Agent3Auditor(
            engine = engine,
            database = db,
            transactionRepository = txnRepo,
        )
        pipeline = IngestionPipeline(
            database = db,
            smsRepository = smsRepo,
            parsedSmsRepository = parsedRepo,
            ingestionLogRepository = ingestionLogRepo,
            agent1 = a1,
            agent2 = a2,
            agent3 = a3,
        )
    }

    @After
    fun tearDown() { db.close() }

    private fun stubAllIgnore() {
        coEvery { engine.generatePredictionTracking(any<String>(), any<String>(), anyNullable<Int>()) } returns
            kotlinx.coroutines.flow.flowOf("""{"kind":"IGNORE","confidence":1.0}""")
        coEvery { engine.generatePrediction(any<String>()) } returns """{"kind":"IGNORE","confidence":1.0}"""
    }

    private fun stubHappyPath() {
        val a1Resp = """{"kind":"TRANSACTION","amountPaise":10000,"currency":"INR","direction":"DEBIT","txnAtMillis":null,"channel":"UPI","sourceKeyHint":null,"merchantRaw":"Acme","cardLast4Hint":null,"accountLast4Hint":null,"referenceNo":null,"confidence":0.95}"""
        val a2Resp = """{"source":{"kind":"new","sourceKey":"VK-TEST","deducedType":"UPI","suggestedBankName":null,"suggestedInstrumentType":"UNKNOWN","suggestedDisplayName":null,"confidence":0.9},"account":{"kind":"new","instrumentType":"ACCOUNT","issuer":"Bank","maskedNumber":"XXXX1234","currency":"INR","confidence":0.9},"merchant":{"kind":"new","name":"Acme","normalizedName":"acme","vpa":null,"confidence":0.9},"a2Confidence":0.9}"""
        val a3Resp = """{"currentDecision":{"decision":"COMMIT"}}"""
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent1.parse"), anyNullable<Int>()) } returns
            kotlinx.coroutines.flow.flowOf(a1Resp)
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent1.parse.retry"), anyNullable<Int>()) } returns
            kotlinx.coroutines.flow.flowOf(a1Resp)
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent2.resolve"), anyNullable<Int>()) } returns
            kotlinx.coroutines.flow.flowOf(a2Resp)
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent2.resolve.retry"), anyNullable<Int>()) } returns
            kotlinx.coroutines.flow.flowOf(a2Resp)
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent3.audit"), anyNullable<Int>()) } returns
            kotlinx.coroutines.flow.flowOf(a3Resp)
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent3.audit.retry"), anyNullable<Int>()) } returns
            kotlinx.coroutines.flow.flowOf(a3Resp)
        coEvery { engine.generatePrediction(any<String>()) } returns a1Resp
    }

    private fun stubA2Throws() {
        val a1Resp = """{"kind":"TRANSACTION","amountPaise":10000,"currency":"INR","direction":"DEBIT","txnAtMillis":null,"channel":"UPI","sourceKeyHint":null,"merchantRaw":"Acme","cardLast4Hint":null,"accountLast4Hint":null,"referenceNo":null,"confidence":0.95}"""
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent1.parse"), anyNullable<Int>()) } returns
            kotlinx.coroutines.flow.flowOf(a1Resp)
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent1.parse.retry"), anyNullable<Int>()) } returns
            kotlinx.coroutines.flow.flowOf(a1Resp)
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent2.resolve"), anyNullable<Int>()) } answers {
            throw IllegalStateException("malformed JSON")
        }
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent2.resolve.retry"), anyNullable<Int>()) } answers {
            throw IllegalStateException("malformed JSON")
        }
        coEvery { engine.generatePrediction(any<String>()) } returns a1Resp
    }

    private fun rawMsg(sender: String, body: String, ts: Long, id: Long = 0L) = RawSmsMessage(
        id = id,
        senderAddress = sender,
        msgBody = body,
        timestamp = ts,
        status = SmsStatus.UNPARSED,
    )

    @Test
    fun `processedAt is set on commit and row is excluded from pending`() = runTest {
        stubHappyPath()
        val now = System.currentTimeMillis()
        val rawId = smsRepo.insert(rawMsg("VK-TEST", "Rs 100 at Acme", now - 1_000L))
        val outcome = pipeline.run(
            source = ListSmsSource(listOf(rawMsg("VK-TEST", "Rs 100 at Acme", now - 1_000L, id = rawId))),
            range = DateRange(0L, Long.MAX_VALUE),
            emit = { },
        )
        assertTrue(outcome is IngestionOutcome.Success)
        val row = smsRepo.getById(rawId)
        assertNotNull(row)
        assertEquals(SmsStatus.PARSED, row!!.status)
        assertNotNull("processedAt should be set on commit", row.processedAt)
        assertNull("lastError should be null on commit", row.lastError)
        // The pending query excludes it.
        assertEquals(0, smsRepo.pendingOnce().size)
        assertEquals(0, smsRepo.pendingInRange(0L, Long.MAX_VALUE).size)
    }

    @Test
    fun `processedAt is set on ignore and row is excluded from pending`() = runTest {
        stubAllIgnore()
        val now = System.currentTimeMillis()
        val rawId = smsRepo.insert(rawMsg("VK-TEST", "OTP 847291", now - 1_000L))
        val outcome = pipeline.run(
            source = ListSmsSource(listOf(rawMsg("VK-TEST", "OTP 847291", now - 1_000L, id = rawId))),
            range = DateRange(0L, Long.MAX_VALUE),
            emit = { },
        )
        assertTrue(outcome is IngestionOutcome.Success)
        val row = smsRepo.getById(rawId)
        assertNotNull(row)
        assertEquals(SmsStatus.IGNORED, row!!.status)
        assertNotNull("processedAt should be set on ignore", row.processedAt)
        // The pending query excludes it.
        assertEquals(0, smsRepo.pendingOnce().size)
        assertEquals(0, smsRepo.pendingInRange(0L, Long.MAX_VALUE).size)
    }

    @Test
    fun `lastError is set on A2 failure and row stays pending`() = runTest {
        stubA2Throws()
        val now = System.currentTimeMillis()
        val rawId = smsRepo.insert(rawMsg("VK-TEST", "Rs 100 at Acme", now - 1_000L))
        val outcome = pipeline.run(
            source = ListSmsSource(listOf(rawMsg("VK-TEST", "Rs 100 at Acme", now - 1_000L, id = rawId))),
            range = DateRange(0L, Long.MAX_VALUE),
            emit = { },
        )
        assertTrue(outcome is IngestionOutcome.Success)
        val summary = (outcome as IngestionOutcome.Success).summary
        assertEquals(1, summary.skippedByA2)
        val row = smsRepo.getById(rawId)
        assertNotNull(row)
        assertEquals(SmsStatus.UNPARSED, row!!.status)
        assertNull("processedAt should stay null on skip", row.processedAt)
        assertNotNull("lastError should be set on skip", row.lastError)
        assertTrue("lastError should mention no parseable JSON: '${row.lastError}'", row.lastError!!.contains("no parseable JSON"))
        // The row is still picked up by the pending query.
        assertEquals(1, smsRepo.pendingOnce().size)
        assertEquals(1, smsRepo.pendingInRange(0L, Long.MAX_VALUE).size)
    }

    @Test
    fun `re-running over processed rows is a no-op`() = runTest {
        stubHappyPath()
        val now = System.currentTimeMillis()
        val rawId = smsRepo.insert(rawMsg("VK-TEST", "Rs 100 at Acme", now - 1_000L))
        // First run commits the row.
        pipeline.run(
            source = ListSmsSource(listOf(rawMsg("VK-TEST", "Rs 100 at Acme", now - 1_000L, id = rawId))),
            range = DateRange(0L, Long.MAX_VALUE),
            emit = { },
        )
        // Capture the engine call count so far (A1 + A2 for the first run).
        val callsBefore = 0
        // Second run: the pending query returns zero rows, so the
        // engine is never called.
        val outcome = pipeline.run(
            source = ListSmsSource(emptyList()),
            range = DateRange(0L, Long.MAX_VALUE),
            emit = { },
        )
        assertTrue(outcome is IngestionOutcome.Success)
        assertEquals(IngestionSummary.EMPTY, (outcome as IngestionOutcome.Success).summary)
        coVerify(exactly = 3) {
            // The first run made 3 engine calls (A1 + A2 + A3, all
            // succeeded on the first try so none needed a retry).
            // The second run made 0 because the pending query
            // returned no rows.
            engine.generatePredictionTracking(any<String>(), any<String>(), anyNullable<Int>())
        }
    }
}
