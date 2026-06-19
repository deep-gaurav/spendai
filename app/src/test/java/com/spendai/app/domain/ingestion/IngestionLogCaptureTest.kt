package com.spendai.app.domain.ingestion

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.IngestionLogA1
import com.spendai.app.data.local.entity.IngestionLogA2
import com.spendai.app.data.local.entity.ParsedSmsKind
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
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
 * Locks down the per-message audit trail: the pipeline writes one
 * [com.spendai.app.data.local.entity.IngestionLog] row per processed
 * SMS, capturing the A1/A2 prompts, raw model responses, outcomes,
 * and any skip reason. The debug pane reads from this table.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, sdk = [33])
class IngestionLogCaptureTest {

    private lateinit var db: AppDatabase
    private lateinit var pipeline: IngestionPipeline
    private lateinit var ingestionLogRepo: IngestionLogRepository
    private val engine: GemmaInferenceEngine = mockk(relaxed = true)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val smsRepo = SmsRepository(db.smsDao())
        val parsedRepo = ParsedSmsRepository(db.parsedSmsDao())
        val sourceRepo = FinancialSourceRepository(db.financialSourceDao())
        val accountRepo = AccountRepository(db.accountDao())
        val categoryRepo = CategoryRepository(db.categoryDao())
        val merchantRepo = MerchantRepository(db.merchantDao(), db.merchantMetadataDao())
        val txnRepo = TransactionRepository(db.transactionDao())
        ingestionLogRepo = IngestionLogRepository(db.ingestionLogDao())
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

    private fun rawMsg(sender: String, body: String, ts: Long, id: Long = 0L) = RawSmsMessage(
        id = id,
        senderAddress = sender,
        msgBody = body,
        timestamp = ts,
        status = SmsStatus.UNPARSED,
    )

    @Test
    fun `happy path writes a COMMITTED log row with prompt and response`() = runTest {
        val a1Resp = """{"kind":"TRANSACTION","amountPaise":10000,"currency":"INR","direction":"DEBIT","txnAtMillis":null,"channel":"UPI","sourceKeyHint":null,"merchantRaw":"Acme","cardLast4Hint":null,"accountLast4Hint":null,"referenceNo":null,"confidence":0.95}"""
        val a2Resp = """{"source":{"kind":"new","sourceKey":"VK-TEST","deducedType":"UPI","suggestedInstrumentType":"UNKNOWN","confidence":0.9},"account":{"kind":"new","instrumentType":"ACCOUNT","issuer":"Bank","maskedNumber":"XXXX1234","currency":"INR","confidence":0.9},"merchant":{"kind":"new","name":"Acme","normalizedName":"acme","vpa":null,"confidence":0.9},"a2Confidence":0.9}"""
        val a3Resp = """{"currentDecision":{"decision":"COMMIT"}}"""
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent1.parse"), anyNullable()) } returns
            kotlinx.coroutines.flow.flowOf(a1Resp)
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent1.parse.retry"), anyNullable()) } returns
            kotlinx.coroutines.flow.flowOf(a1Resp)
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent2.resolve"), anyNullable()) } returns
            kotlinx.coroutines.flow.flowOf(a2Resp)
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent2.resolve.retry"), anyNullable()) } returns
            kotlinx.coroutines.flow.flowOf(a2Resp)
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent3.audit"), anyNullable()) } returns
            kotlinx.coroutines.flow.flowOf(a3Resp)
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent3.audit.retry"), anyNullable()) } returns
            kotlinx.coroutines.flow.flowOf(a3Resp)
        coEvery { engine.generatePrediction(any<String>()) } returns a1Resp

        val now = System.currentTimeMillis()
        pipeline.run(
            source = ListSmsSource(listOf(rawMsg("VK-TEST", "Rs 100 at Acme", now, id = 1L))),
            range = DateRange(0L, Long.MAX_VALUE),
            emit = { },
        )

        val logs = ingestionLogRepo.getRecent()
        assertEquals(1, logs.size)
        val log = logs[0]
        assertEquals(IngestionLogA1.OK, log.a1Outcome)
        assertEquals(IngestionLogA2.COMMITTED, log.a2Outcome)
        assertNotNull("A1 prompt should be captured", log.a1Prompt)
        assertNotNull("A1 response should be captured", log.a1Response)
        assertNotNull("A2 prompt should be captured", log.a2Prompt)
        assertNotNull("A2 response should be captured", log.a2Response)
        assertTrue("A1 prompt should contain 'You are a private'", log.a1Prompt!!.contains("You are a private"))
        assertTrue("A2 prompt should contain 'knownMerchants'", log.a2Prompt!!.contains("knownMerchants"))
        assertNotNull("transactionId should be linked", log.transactionId)
        assertEquals(0.9f, log.a2Confidence!!, 0.0001f)
    }

    @Test
    fun `IGNORE message writes a row with a2Outcome NOT_RUN`() = runTest {
        coEvery { engine.generatePredictionTracking(any<String>(), any<String>(), anyNullable()) } returns
            kotlinx.coroutines.flow.flowOf("""{"kind":"IGNORE","confidence":1.0}""")
        coEvery { engine.generatePrediction(any<String>()) } returns """{"kind":"IGNORE","confidence":1.0}"""

        val now = System.currentTimeMillis()
        pipeline.run(
            source = ListSmsSource(listOf(rawMsg("VK-TEST", "Your OTP is 847291", now, id = 1L))),
            range = DateRange(0L, Long.MAX_VALUE),
            emit = { },
        )

        val logs = ingestionLogRepo.getRecent()
        assertEquals(1, logs.size)
        val log = logs[0]
        assertEquals(IngestionLogA1.IGNORE, log.a1Outcome)
        assertEquals(IngestionLogA2.NOT_RUN, log.a2Outcome)
        assertNotNull("A1 prompt should be captured", log.a1Prompt)
        assertNotNull("A1 response should be captured", log.a1Response)
        assertEquals(null, log.transactionId)
    }

    @Test
    fun `A2 failure writes a SKIPPED_A2 row with the error captured`() = runTest {
        val a1Resp = """{"kind":"TRANSACTION","amountPaise":10000,"currency":"INR","direction":"DEBIT","txnAtMillis":null,"channel":"UPI","sourceKeyHint":null,"merchantRaw":"Acme","cardLast4Hint":null,"accountLast4Hint":null,"referenceNo":null,"confidence":0.95}"""
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent1.parse"), anyNullable()) } returns
            kotlinx.coroutines.flow.flowOf(a1Resp)
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent1.parse.retry"), anyNullable()) } returns
            kotlinx.coroutines.flow.flowOf(a1Resp)
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent2.resolve"), anyNullable()) } answers {
            throw IllegalStateException("no parseable JSON")
        }
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent2.resolve.retry"), anyNullable()) } answers {
            throw IllegalStateException("no parseable JSON")
        }
        coEvery { engine.generatePrediction(any<String>()) } returns a1Resp

        val now = System.currentTimeMillis()
        pipeline.run(
            source = ListSmsSource(listOf(rawMsg("VK-TEST", "Rs 100 at Acme", now, id = 1L))),
            range = DateRange(0L, Long.MAX_VALUE),
            emit = { },
        )

        val logs = ingestionLogRepo.getRecent()
        assertEquals(1, logs.size)
        val log = logs[0]
        assertEquals(IngestionLogA1.OK, log.a1Outcome)
        assertEquals(IngestionLogA2.SKIPPED_A2, log.a2Outcome)
        assertNotNull("A2 error should be captured", log.a2Error)
        assertTrue("A2 error should mention parse failure", log.a2Error!!.contains("no parseable JSON"))
        assertEquals(null, log.transactionId)
    }

    @Test
    fun `cache hit with no existing transaction re-runs A2 and captures the new attempt`() = runTest {
        // Pre-seed raw_sms + a parsed_sms row (cache hit) but no
        // transaction. The pipeline should re-run A2 and either
        // commit or log the new SKIPPED_A2 with the latest
        // prompt/response.
        val smsRepo = SmsRepository(db.smsDao())
        val parsedRepo = ParsedSmsRepository(db.parsedSmsDao())
        val now = System.currentTimeMillis()
        val rawId = smsRepo.insert(
            com.spendai.app.data.local.entity.RawSmsMessage(
                senderAddress = "VK-TEST",
                msgBody = "Rs 100 at Acme",
                timestamp = now,
                status = com.spendai.app.data.local.entity.SmsStatus.UNPARSED,
            )
        )
        val parsedId = parsedRepo.insert(
            com.spendai.app.data.local.entity.ParsedSms(
                rawSmsId = rawId,
                parsedAt = now,
                kind = com.spendai.app.data.local.entity.ParsedSmsKind.TRANSACTION.name,
                amountPaise = 10000L,
                currency = "INR",
                direction = "DEBIT",
                channel = "UPI",
                merchantRaw = "Acme",
                a1Confidence = 0.95f,
                a1RawJson = "{\"kind\":\"TRANSACTION\",...}",
            )
        )

        // A2 throws on the retry (still bad).
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent2.resolve"), anyNullable()) } answers {
            throw IllegalStateException("still no parseable JSON")
        }
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent2.resolve.retry"), anyNullable()) } answers {
            throw IllegalStateException("still no parseable JSON")
        }

        val outcome = pipeline.run(
            source = ListSmsSource(listOf(
                com.spendai.app.data.local.entity.RawSmsMessage(
                    id = rawId,
                    senderAddress = "VK-TEST",
                    msgBody = "Rs 100 at Acme",
                    timestamp = now,
                    status = com.spendai.app.data.local.entity.SmsStatus.UNPARSED,
                )
            )),
            range = DateRange(0L, Long.MAX_VALUE),
            emit = { },
        )
        assertTrue(outcome is IngestionOutcome.Success)
        val summary = (outcome as IngestionOutcome.Success).summary
        assertEquals(1, summary.skippedByA2)

        val logs = ingestionLogRepo.getRecent()
        assertEquals(1, logs.size)
        val log = logs[0]
        // The A1 prompt is null (not persisted on cache hit) but the
        // A1 response is the persisted a1RawJson, and A2 is recorded
        // as the new attempt's failure.
        assertEquals(IngestionLogA1.OK, log.a1Outcome)
        assertNotNull("cached A1 response should be surfaced from a1RawJson", log.a1Response)
        assertEquals(IngestionLogA2.SKIPPED_A2, log.a2Outcome)
        assertNotNull("A2 error should be captured on retry", log.a2Error)
        assertTrue(log.a2Error!!.contains("no parseable JSON"))
        // The A2 prompt is null because the engine threw before we
        // could build the user message (the resolver's first call
        // constructs fullPrompt from A2_SYSTEM_INSTRUCTION + user
        // message; if generatePredictionTracking throws, we never
        // reach the return path that captures the text). This is
        // acceptable — the user can still see the new error.
    }

    @Test
    fun `A2 failure captures the prompt and partial response via A2FailureException`() = runTest {
        val a1Resp = """{"kind":"TRANSACTION","amountPaise":10000,"currency":"INR","direction":"DEBIT","txnAtMillis":null,"channel":"UPI","sourceKeyHint":null,"merchantRaw":"Acme","cardLast4Hint":null,"accountLast4Hint":null,"referenceNo":null,"confidence":0.95}"""
        // First attempt returns garbage; retry returns nothing parseable.
        // The resolver must carry the prompt and the partial response
        // through to the audit log via A2FailureException.
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent1.parse"), anyNullable()) } returns
            kotlinx.coroutines.flow.flowOf(a1Resp)
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent1.parse.retry"), anyNullable()) } returns
            kotlinx.coroutines.flow.flowOf(a1Resp)
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent2.resolve"), anyNullable()) } returns
            kotlinx.coroutines.flow.flowOf("not json at all")
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent2.resolve.retry"), anyNullable()) } returns
            kotlinx.coroutines.flow.flowOf("still not json")
        coEvery { engine.generatePrediction(any<String>()) } returns a1Resp

        val now = System.currentTimeMillis()
        val outcome = pipeline.run(
            source = ListSmsSource(listOf(
                rawMsg("VK-TEST", "Rs 100 at Acme", now, id = 1L)
            )),
            range = DateRange(0L, Long.MAX_VALUE),
            emit = { },
        )
        assertTrue(outcome is IngestionOutcome.Success)
        val logs = ingestionLogRepo.getRecent()
        assertEquals(1, logs.size)
        val log = logs[0]
        assertEquals(IngestionLogA2.SKIPPED_A2, log.a2Outcome)
        // The fix: prompt and response must ALWAYS be captured.
        assertNotNull("A2 prompt must be captured on failure", log.a2Prompt)
        assertNotNull("A2 response must be captured on failure", log.a2Response)
        assertTrue("A2 prompt should contain the system instruction",
            log.a2Prompt!!.contains("a2Confidence"))
        assertEquals("still not json", log.a2Response)
        assertNotNull("A2 error should be captured", log.a2Error)
    }

}
