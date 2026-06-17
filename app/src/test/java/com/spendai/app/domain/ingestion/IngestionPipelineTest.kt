package com.spendai.app.domain.ingestion

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.ParsedSms
import com.spendai.app.data.local.entity.ParsedSmsKind
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SmsStatus
import com.spendai.app.data.local.entity.Transaction
import com.spendai.app.data.local.entity.TransactionStatus
import com.spendai.app.data.repository.AccountRepository
import com.spendai.app.data.repository.FinancialSourceRepository
import com.spendai.app.data.repository.MerchantRepository
import com.spendai.app.data.repository.ParsedSmsRepository
import com.spendai.app.data.repository.PendingReviewRepository
import com.spendai.app.data.repository.SmsRepository
import com.spendai.app.data.repository.TransactionLinkRepository
import com.spendai.app.data.repository.TransactionRepository
import com.spendai.app.domain.agent.Agent1SmsParser
import com.spendai.app.domain.agent.Agent2EntityResolver
import com.spendai.app.domain.agent.Agent3DayCommitter
import com.spendai.app.domain.ingestion.sources.ListSmsSource
import com.spendai.app.inference.GemmaInferenceEngine
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
import com.spendai.app.TestApp

/**
 * Locks down the [IngestionPipeline] behaviour end-to-end with a
 * real v2 [AppDatabase] and a mocked [GemmaInferenceEngine]. This
 * is the test that proves the testability win: the same code path
 * the foreground service and the worker call into, exercised
 * without Android UI, without foreground notifications, and
 * without spending a minute on a real LLM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, sdk = [33])
class IngestionPipelineTest {

    private lateinit var db: AppDatabase
    private lateinit var pipeline: IngestionPipeline
    private lateinit var smsRepo: SmsRepository
    private lateinit var txnRepo: TransactionRepository
    private lateinit var linkRepo: TransactionLinkRepository
    private lateinit var reviewRepo: PendingReviewRepository
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
        val merchantRepo = MerchantRepository(db.merchantDao())
        txnRepo = TransactionRepository(db.transactionDao())
        linkRepo = TransactionLinkRepository(db.transactionLinkDao())
        reviewRepo = PendingReviewRepository(db.pendingReviewDao())
        coEvery { engine.state } returns MutableStateFlow(
            com.spendai.app.inference.InferenceState.Ready("NPU")
        )
        val a1 = Agent1SmsParser(engine, parsedRepo)
        val a2 = Agent2EntityResolver(engine, sourceRepo, accountRepo, merchantRepo, txnRepo)
        val a3 = Agent3DayCommitter(engine)
        pipeline = IngestionPipeline(
            database = db,
            smsRepository = smsRepo,
            parsedSmsRepository = parsedRepo,
            agent1 = a1,
            agent2 = a2,
            agent3 = a3,
        )
    }

    @After
    fun tearDown() { db.close() }

    // ----- helpers -----

    /** Wire the engine to return IGNORE for every input. */
    private fun stubAllIgnore() {
        coEvery { engine.generatePredictionTracking(any<String>(), any<String>()) } returns
            kotlinx.coroutines.flow.flowOf("""{"kind":"IGNORE","confidence":1.0}""")
        coEvery { engine.generatePrediction(any<String>()) } returns """{"kind":"IGNORE","confidence":1.0}"""
        coEvery { engine.probe(any<String>()) } returns """{"commits":[]}"""
    }

    /**
     * Wire the engine so:
     *  - A1 calls (even index 0,2,4,...) return a TRANSACTION
     *  - A2 calls (odd index 1,3,5,...) return all-new source/account/merchant
     *  - A3 (probe) returns a single commit for any parsedSmsId
     *
     * The pipeline calls A1, A2, A1, A2, ... in sequence per message,
     * so we alternate.
     */
    private fun stubHappyPath(dayBucket: String) {
        val a1Resp = """{"kind":"TRANSACTION","amountPaise":10000,"currency":"INR","direction":"DEBIT","txnAtMillis":null,"channel":"UPI","sourceKeyHint":null,"merchantRaw":"Acme","cardLast4Hint":null,"accountLast4Hint":null,"referenceNo":null,"confidence":0.95}"""
        val a2Resp = """{"source":{"kind":"new","sourceKey":"VK-TEST","deducedType":"UPI","suggestedBankName":null,"suggestedInstrumentType":"UNKNOWN","suggestedDisplayName":null,"confidence":0.9},"account":{"kind":"new","instrumentType":"ACCOUNT","issuer":"Test Bank","maskedNumber":"XXXX1234","currency":"INR","confidence":0.9},"merchant":{"kind":"new","name":"Acme","normalizedName":"acme","vpa":null,"confidence":0.9},"possibleLink":null,"a2Confidence":0.9}"""
        var callIndex = 0
        coEvery { engine.generatePredictionTracking(any<String>(), any<String>()) } answers {
            val n = callIndex++
            kotlinx.coroutines.flow.flowOf(if (n % 2 == 0) a1Resp else a2Resp)
        }
        coEvery { engine.generatePrediction(any<String>()) } answers {
            val n = callIndex++
            if (n % 2 == 0) a1Resp else a2Resp
        }
        coEvery { engine.probe(any<String>()) } answers {
            val prompt = firstArg<String>()
            val parsedSmsIdPattern = """parsedSmsId":\s*(\d+)""".toRegex()
            val match = parsedSmsIdPattern.find(prompt)
            val id = match?.groupValues?.get(1)?.toLong() ?: 1L
            """{"commits":[{"parsedSmsId":$id,"finalTransaction":{"accountId":1,"merchantId":1,"rawSmsId":$id,"parsedSmsId":$id,"amountPaise":10000,"currency":"INR","direction":"DEBIT","txnAtMillis":1,"channel":"UPI","referenceNo":null,"status":"CONFIRMED","notes":null},"confidence":0.9,"linksToCreate":[],"needsReview":false}]}"""
        }
    }

    private fun rawMsg(sender: String, body: String, ts: Long, id: Long = 0L) = RawSmsMessage(
        id = id,
        senderAddress = sender,
        msgBody = body,
        timestamp = ts,
        status = SmsStatus.UNPARSED,
    )

    private fun collectProgress(): Pair<MutableList<IngestionProgress>, ((IngestionProgress) -> Unit)> {
        val collected = mutableListOf<IngestionProgress>()
        val emit: (IngestionProgress) -> Unit = { collected += it }
        return collected to emit
    }

    // ----- tests -----

    @Test
    fun `empty source produces empty summary and Done event`() = runTest {
        stubAllIgnore()
        val (events, emit) = collectProgress()
        val outcome = pipeline.run(
            source = ListSmsSource(emptyList()),
            range = DateRange(0L, Long.MAX_VALUE),
            emit = { emit(it) },
        )
        assertTrue(outcome is IngestionOutcome.Success)
        assertEquals(IngestionSummary.EMPTY, (outcome as IngestionOutcome.Success).summary)
        assertTrue(events.last() is IngestionProgress.Done)
    }

    @Test
    fun `all-IGNORE source runs agents but produces no transactions`() = runTest {
        stubAllIgnore()
        val (events, emit) = collectProgress()
        val now = System.currentTimeMillis()
        val source = ListSmsSource(listOf(
            rawMsg("VK-TEST", "OTP for txn 847291", now - 1_000L, id = 1L),
            rawMsg("VK-TEST", "OTP for txn 847292", now - 2_000L, id = 2L),
        ))
        val outcome = pipeline.run(
            source = source,
            range = DateRange(0L, Long.MAX_VALUE),
            emit = { emit(it) },
        )
        assertTrue(outcome is IngestionOutcome.Success)
        val summary = (outcome as IngestionOutcome.Success).summary
        assertEquals(2, summary.totalMessages)
        assertEquals(0, summary.parsed)
        assertEquals(2, summary.ignored)
        assertEquals(0, summary.committedTransactions)
        // A1 still ran twice — count the MessageParsed events.
        val parsed = events.filterIsInstance<IngestionProgress.MessageParsed>()
        assertEquals(2, parsed.size)
    }

    @Test
    fun `happy path produces committed transactions and emits progress events`() = runTest {
        stubHappyPath("today")
        val (events, emit) = collectProgress()
        val now = System.currentTimeMillis()
        val source = ListSmsSource(listOf(
            rawMsg("VK-TEST", "Rs 100 spent at Acme", now - 1_000L, id = 1L),
        ))
        val outcome = pipeline.run(
            source = source,
            range = DateRange(0L, Long.MAX_VALUE),
            emit = { emit(it) },
        )
        assertTrue(outcome is IngestionOutcome.Success)
        val summary = (outcome as IngestionOutcome.Success).summary
        assertEquals(1, summary.totalMessages)
        assertEquals(1, summary.parsed)
        assertEquals(1, summary.committedTransactions)
        // DB has the transaction.
        val txns = txnRepo.getSince(0L)
        assertEquals(1, txns.size)
        assertEquals(TransactionStatus.CONFIRMED.name, txns[0].status)
        // Progress events: at least one MessageParsed + one MessageResolved + one CommittingDay + one DayCommitted + Done
        assertTrue(events.any { it is IngestionProgress.MessageParsed })
        assertTrue(events.any { it is IngestionProgress.MessageResolved })
        assertTrue(events.any { it is IngestionProgress.CommittingDay })
        assertTrue(events.any { it is IngestionProgress.DayCommitted })
        assertTrue(events.last() is IngestionProgress.Done)
    }

    @Test
    fun `multi-day range groups by local day and commits per day`() = runTest {
        stubHappyPath("today")
        val (events, emit) = collectProgress()
        val now = System.currentTimeMillis()
        val oneDay = 24L * 3_600_000L
        // 3 messages on 3 different days
        val source = ListSmsSource(listOf(
            rawMsg("VK-TEST", "day1", now - oneDay * 2, id = 1L),
            rawMsg("VK-TEST", "day2", now - oneDay * 1, id = 2L),
            rawMsg("VK-TEST", "day3", now - oneDay * 0, id = 3L),
        ))
        val outcome = pipeline.run(
            source = source,
            range = DateRange(0L, now + oneDay),
            emit = { emit(it) },
        )
        assertTrue(outcome is IngestionOutcome.Success)
        val summary = (outcome as IngestionOutcome.Success).summary
        assertEquals(3, summary.totalMessages)
        assertEquals(3, summary.parsed)
        // 3 CommittingDay events
        val commits = events.filterIsInstance<IngestionProgress.CommittingDay>()
        assertEquals(3, commits.size)
        assertEquals(3, txnRepo.getSince(0L).size)
    }

    @Test
    fun `A2 malformed JSON skips the message and continues`() = runTest {
        // A1 -> TRANSACTION, A2 -> throw (malformed JSON the parser
        // can't recover from). The pipeline should emit a
        // MessageSkipped event, increment skippedByA2, and move on
        // to the next message.
        val a1Resp = """{"kind":"TRANSACTION","amountPaise":10000,"currency":"INR","direction":"DEBIT","txnAtMillis":null,"channel":"UPI","sourceKeyHint":null,"merchantRaw":"Acme","cardLast4Hint":null,"accountLast4Hint":null,"referenceNo":null,"confidence":0.95}"""
        // A1 must always succeed (the new Agent1SmsParser propagates
        // engine exceptions instead of swallowing them, so a throw on
        // the A1 call would surface as skippedByA1). A2 throws on
        // every call to simulate malformed JSON.
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent1.parse")) } returns
            kotlinx.coroutines.flow.flowOf(a1Resp)
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent1.parse.retry")) } returns
            kotlinx.coroutines.flow.flowOf(a1Resp)
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent2.resolve")) } answers {
            throw IllegalStateException("Malformed JSON: unexpected EOF")
        }
        coEvery { engine.generatePredictionTracking(any<String>(), eq("agent2.resolve.retry")) } answers {
            throw IllegalStateException("Malformed JSON: unexpected EOF")
        }
        coEvery { engine.generatePrediction(any<String>()) } returns a1Resp
        // A3 isn't called because nothing materialises.

        val (events, emit) = collectProgress()
        val now = System.currentTimeMillis()
        val source = ListSmsSource(listOf(
            rawMsg("VK-A", "Rs 100 at Acme", now - 1_000L, id = 1L),
            rawMsg("VK-B", "Rs 200 at Beta", now - 2_000L, id = 2L),
        ))
        val outcome = pipeline.run(
            source = source,
            range = DateRange(0L, Long.MAX_VALUE),
            emit = { emit(it) },
        )

        // Pipeline completes successfully, with skipped count == 2
        assertTrue(outcome is IngestionOutcome.Success)
        val summary = (outcome as IngestionOutcome.Success).summary
        assertEquals(2, summary.totalMessages)
        assertEquals(2, summary.parsed)
        assertEquals(0, summary.committedTransactions)
        assertEquals(2, summary.skippedByA2)
        // No transactions were written.
        assertEquals(0, txnRepo.getSince(0L).size)
        // Two skip events were emitted.
        val skips = events.filterIsInstance<IngestionProgress.MessageSkipped>()
        assertEquals(2, skips.size)
        assertTrue(
            "expected 'no parseable JSON' in reason, got: ${skips[0].reason}",
            skips[0].reason.contains("no parseable JSON"),
        )
        // Raw SMS rows are still UNPARSED so a future run can retry.
        val open = smsRepo.unparsedOnce()
        assertEquals(2, open.size)
    }

    @Test
    fun `low-confidence commit lands in pending_review not transactions`() = runTest {
        // A1 = TRANSACTION, A2 = low confidence, A3 = needsReview=true
        val a1Resp = """{"kind":"TRANSACTION","amountPaise":500,"currency":"INR","direction":"DEBIT","txnAtMillis":null,"channel":"UPI","sourceKeyHint":null,"merchantRaw":"Acme","cardLast4Hint":null,"accountLast4Hint":null,"referenceNo":null,"confidence":0.95}"""
        val a2Resp = """{"source":{"kind":"new","sourceKey":"VK-TEST","deducedType":"UPI","suggestedBankName":null,"suggestedInstrumentType":"UNKNOWN","suggestedDisplayName":null,"confidence":0.4},"account":{"kind":"new","instrumentType":"ACCOUNT","issuer":"Test","maskedNumber":"XXXX1","currency":"INR","confidence":0.4},"merchant":{"kind":"new","name":"Acme","normalizedName":"acme","vpa":null,"confidence":0.4},"possibleLink":null,"a2Confidence":0.4}"""
        var callIndex = 0
        coEvery { engine.generatePredictionTracking(any<String>(), any<String>()) } answers {
            val n = callIndex++
            kotlinx.coroutines.flow.flowOf(if (n % 2 == 0) a1Resp else a2Resp)
        }
        coEvery { engine.generatePrediction(any<String>()) } answers {
            val n = callIndex++
            if (n % 2 == 0) a1Resp else a2Resp
        }
        coEvery { engine.probe(any<String>()) } returns """{"commits":[{"parsedSmsId":1,"finalTransaction":{"accountId":1,"merchantId":1,"rawSmsId":1,"parsedSmsId":1,"amountPaise":500,"currency":"INR","direction":"DEBIT","txnAtMillis":1,"channel":"UPI","referenceNo":null,"status":"NEEDS_REVIEW","notes":null},"confidence":0.4,"linksToCreate":[],"needsReview":true}]}"""

        val (events, emit) = collectProgress()
        val now = System.currentTimeMillis()
        val source = ListSmsSource(listOf(
            rawMsg("VK-TEST", "Rs 5 at Acme", now - 1000L, id = 1L),
        ))
        val outcome = pipeline.run(
            source = source,
            range = DateRange(0L, Long.MAX_VALUE),
            emit = { emit(it) },
        )
        assertTrue(outcome is IngestionOutcome.Success)
        val summary = (outcome as IngestionOutcome.Success).summary
        assertEquals(1, summary.needsReview)
        // Transaction is in the DB with NEEDS_REVIEW status
        val txns = txnRepo.getSince(0L)
        assertEquals(1, txns.size)
        assertEquals(TransactionStatus.NEEDS_REVIEW.name, txns[0].status)
        // And a pending_review row
        val open = reviewRepo.getOpenOnce()
        assertEquals(1, open.size)
        assertEquals(1L, open[0].targetId)
    }
}
