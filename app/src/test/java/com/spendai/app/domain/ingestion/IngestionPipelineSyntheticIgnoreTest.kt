package com.spendai.app.domain.ingestion

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.ParsedSms
import com.spendai.app.data.local.entity.ParsedSmsKind
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SmsStatus
import com.spendai.app.data.repository.AccountRepository
import com.spendai.app.data.repository.IngestionLogRepository
import com.spendai.app.data.repository.FinancialSourceRepository
import com.spendai.app.data.repository.MerchantRepository
import com.spendai.app.data.repository.ParsedSmsRepository
import com.spendai.app.data.repository.SmsRepository
import com.spendai.app.data.repository.TransactionRepository
import com.spendai.app.domain.agent.Agent1SmsParser
import com.spendai.app.domain.agent.Agent2EntityResolver
import com.spendai.app.domain.ingestion.sources.ListSmsSource
import com.spendai.app.inference.GemmaInferenceEngine
import com.spendai.app.inference.InferenceState
import com.spendai.app.TestApp
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Locks down the synthetic-IGNORE recovery contract added in v3.
 *
 * Pre-v3, a per-message [com.google.ai.edge.litertlm.LiteRtLmJniException]
 * in the engine was swallowed by [Agent1SmsParser] and replaced with
 * `A1Contract(kind="IGNORE", confidence=0.0)`. The agent then persisted
 * a [ParsedSms] row with empty `a1RawJson` and `a1Confidence=0.0`,
 * which the pipeline treated as a legitimate IGNORE and used as a
 * cache hit on every subsequent run.
 *
 * The recovery is based on the fact that real model IGNOREs always
 * have `a1RawJson` non-empty (the model wrote SOMETHING, even if the
 * JSON was unparseable) and `a1Confidence = 1.0` per the A1 prompt
 * rules. Rows with the synthetic signature are treated as a cache
 * miss, the placeholder is deleted, and A1 runs again.
 *
 * Phase 3 trimmed A3 from the pipeline, but the synthetic-IGNORE
 * recovery is still exercised here because it lives in the per-message
 * path before A2 is even called.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, sdk = [33])
class IngestionPipelineSyntheticIgnoreTest {

    private lateinit var db: AppDatabase
    private lateinit var pipeline: IngestionPipeline
    private lateinit var smsRepo: SmsRepository
    private lateinit var parsedRepo: ParsedSmsRepository
    private lateinit var txnRepo: TransactionRepository
    private val engine: GemmaInferenceEngine = mockk(relaxed = true)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        smsRepo = SmsRepository(db.smsDao())
        parsedRepo = ParsedSmsRepository(db.parsedSmsDao())
        val sourceRepo = FinancialSourceRepository(db.financialSourceDao())
        val accountRepo = AccountRepository(db.accountDao())
        val merchantRepo = MerchantRepository(db.merchantDao())
        txnRepo = TransactionRepository(db.transactionDao())
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
        )
        pipeline = IngestionPipeline(
            database = db,
            smsRepository = smsRepo,
            parsedSmsRepository = parsedRepo,
            ingestionLogRepository = ingestionLogRepo,
            agent1 = a1,
            agent2 = a2,
        )
    }

    @After
    fun tearDown() { db.close() }

    private fun rawMsg(sender: String, body: String, ts: Long, id: Long = 0L) = RawSmsMessage(
        id = id,
        senderAddress = sender,
        msgBody = body,
        timestamp = ts,
        status = SmsStatus.UNPARSED,
    )

    private fun stubAllIgnore() {
        coEvery { engine.generatePredictionTracking(any<String>(), any<String>(), anyNullable<Int>()) } returns
            kotlinx.coroutines.flow.flowOf("""{"kind":"IGNORE","confidence":1.0}""")
        coEvery { engine.generatePrediction(any<String>()) } returns """{"kind":"IGNORE","confidence":1.0}"""
    }

    private fun stubA1Fails() {
        // A1 always throws LiteRtLmJniException. The agent must
        // propagate it to the pipeline.
        coEvery { engine.generatePredictionTracking(any<String>(), any<String>(), anyNullable<Int>()) } returns
            kotlinx.coroutines.flow.flow<String> { throw com.google.ai.edge.litertlm.LiteRtLmJniException("simulated") }
    }

    /**
     * A cached synthetic-IGNORE (a1RawJson empty, a1Confidence 0.0)
     * is treated as a cache miss. The placeholder row is deleted and
     * A1 runs again. With a happy-path engine the message ends up
     * committed, proving the recovery is real.
     */
    @Test
    fun `synthetic IGNORE in cache triggers re-parse and recovery`() = runTest {
        var a1CallCount = 0
        coEvery { engine.generatePredictionTracking(any<String>(), any<String>(), anyNullable<Int>()) } answers {
            a1CallCount++
            kotlinx.coroutines.flow.flowOf("""{"kind":"IGNORE","confidence":1.0}""")
        }
        coEvery { engine.generatePrediction(any<String>()) } returns """{"kind":"IGNORE","confidence":1.0}"""

        val now = System.currentTimeMillis()
        val rawId = smsRepo.insert(rawMsg("VK-TEST", "Rs 100 at Acme", now - 1000L))
        parsedRepo.insert(
            ParsedSms(
                rawSmsId = rawId,
                parsedAt = now - 500L,
                kind = ParsedSmsKind.IGNORE.name,
                a1Confidence = 0.0f,
                a1RawJson = "",
            )
        )

        val outcome = pipeline.run(
            source = com.spendai.app.domain.ingestion.sources.DatabaseSmsSource(smsRepo),
            range = DateRange(0L, Long.MAX_VALUE),
            emit = { /* no UI surface in unit test */ },
        )
        assertTrue("outcome was $outcome", outcome is IngestionOutcome.Success)
        val summary = (outcome as IngestionOutcome.Success).summary
        assertEquals(1, a1CallCount)
        assertEquals(1, summary.ignored)
        assertEquals(0, summary.committedTransactions)
        val allParsed = parsedRepo.getByRawSms(rawId)
        assertNotNull("expected new parsed_sms row", allParsed)
        assertEquals(1.0f, allParsed!!.a1Confidence)
        assertTrue("a1RawJson should be non-empty after recovery: '${allParsed.a1RawJson}'", allParsed.a1RawJson.isNotEmpty())
    }

    /**
     * A cached real IGNORE (a1RawJson non-empty, a1Confidence 1.0)
     * IS honoured as a cache hit. A1 is not re-run, and the message
     * is counted as ignored.
     */
    @Test
    fun `real IGNORE in cache is honoured as a cache hit`() = runTest {
        stubAllIgnore()
        val now = System.currentTimeMillis()
        val rawId = smsRepo.insert(rawMsg("VK-TEST", "OTP 847291", now - 1000L))
        val realJson = """{"kind":"IGNORE","confidence":1.0}"""
        parsedRepo.insert(
            ParsedSms(
                rawSmsId = rawId,
                parsedAt = now - 500L,
                kind = ParsedSmsKind.IGNORE.name,
                a1Confidence = 1.0f,
                a1RawJson = realJson,
            )
        )
        val source = ListSmsSource(listOf(rawMsg("VK-TEST", "OTP 847291", now - 1000L, id = rawId)))

        val outcome = pipeline.run(
            source = source,
            range = DateRange(0L, Long.MAX_VALUE),
            emit = { },
        )
        assertTrue(outcome is IngestionOutcome.Success)
        val summary = (outcome as IngestionOutcome.Success).summary
        assertEquals(1, summary.totalMessages)
        assertEquals(0, summary.parsed)
        assertEquals(1, summary.ignored)
        assertEquals(0, txnRepo.getSince(0L).size)
    }

    /**
     * Per-message engine failure increments `skippedByA1` and leaves
     * the raw_sms row UNPARSED so a future run can retry. A2 is
     * never reached for a failed message.
     */
    @Test
    fun `per-message engine failure leaves row UNPARSED for retry`() = runTest {
        stubA1Fails()
        val now = System.currentTimeMillis()
        val source = ListSmsSource(listOf(
            rawMsg("VK-A", "Rs 100 at Acme", now - 1000L, id = 1L),
            rawMsg("VK-B", "Rs 200 at Beta", now - 2000L, id = 2L),
        ))

        val events = mutableListOf<IngestionProgress>()
        val outcome = pipeline.run(
            source = source,
            range = DateRange(0L, Long.MAX_VALUE),
            emit = { events += it },
        )
        assertTrue(outcome is IngestionOutcome.Success)
        val summary = (outcome as IngestionOutcome.Success).summary
        assertEquals(2, summary.totalMessages)
        assertEquals(0, summary.parsed)
        assertEquals(0, summary.ignored)
        assertEquals(2, summary.skippedByA1)
        val skips = events.filterIsInstance<IngestionProgress.MessageSkipped>()
        assertEquals(2, skips.size)
        assertEquals(0, txnRepo.getSince(0L).size)
        val open = smsRepo.unparsedOnce()
        assertEquals(2, open.size)
    }
}
