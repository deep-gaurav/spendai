package com.spendai.app.domain.agent

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spendai.app.TestApp
import com.spendai.app.data.local.AppDatabase
import com.spendai.app.data.local.entity.ParsedSms
import com.spendai.app.data.local.entity.ParsedSmsKind
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SmsStatus
import com.spendai.app.data.repository.ParsedSmsRepository
import com.spendai.app.inference.GemmaInferenceEngine
import com.spendai.app.inference.InferenceState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
 * Locks down the v6 "race between two ingestion runs" contract for
 * [Agent1SmsParser]. If a second run (e.g. the worker + the
 * service racing) managed to insert a `parsed_sms` row first,
 * the agent's insert returns `-1` from Room's
 * `OnConflictStrategy.IGNORE`. The agent must:
 *  - NOT throw a UNIQUE constraint violation
 *  - re-fetch the existing row via
 *    [ParsedSmsRepository.getByRawSms] and return it as the
 *    [A1Outcome.parsed]
 *  - keep the prompt + response for this attempt so the audit
 *    log shows what the second run did
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApp::class, sdk = [33])
class Agent1SmsParserDuplicateInsertTest {

    private val rawSms = RawSmsMessage(
        id = 1L,
        senderAddress = "VK-TEST",
        msgBody = "Rs.100 spent at Acme",
        timestamp = 1_700_000_000_000L,
        status = SmsStatus.UNPARSED,
    )

    @After
    fun tearDown() { io.mockk.unmockkAll() }

    @Test
    fun `duplicate parsed_sms insert is safe and returns existing row`() = runBlocking {
        val engine = mockk<GemmaInferenceEngine>()
        every { engine.state } returns MutableStateFlow(InferenceState.Ready("GPU"))
        val a1Json = """{"kind":"TRANSACTION","amountPaise":10000,"currency":"INR","direction":"DEBIT","txnAtMillis":null,"channel":"UPI","sourceKeyHint":null,"merchantRaw":"Acme","cardLast4Hint":null,"accountLast4Hint":null,"referenceNo":null,"confidence":0.95}"""
        coEvery {
            engine.generatePredictionTracking(any<String>(), any<String>(), anyNullable<Int>())
        } returns flowOf(a1Json)

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val smsRepo = com.spendai.app.data.repository.SmsRepository(db.smsDao())
            // Pre-insert the raw_sms row so the parsed_sms FK
            // resolves. The test is about the unique-insert race,
            // not the foreign key.
            smsRepo.insert(
                RawSmsMessage(
                    id = rawSms.id,
                    senderAddress = rawSms.senderAddress,
                    msgBody = rawSms.msgBody,
                    timestamp = rawSms.timestamp,
                    status = SmsStatus.UNPARSED,
                )
            )
            val realRepo = ParsedSmsRepository(db.parsedSmsDao())
            val seedId = realRepo.insert(
                ParsedSms(
                    rawSmsId = rawSms.id,
                    parsedAt = 1_700_000_000_000L,
                    kind = ParsedSmsKind.TRANSACTION.name,
                    amountPaise = 10000L,
                    currency = "INR",
                    direction = "DEBIT",
                    channel = "UPI",
                    merchantRaw = "Acme",
                    a1Confidence = 0.95f,
                    a1RawJson = a1Json,
                )
            )
            assertTrue("pre-seed insert should succeed", seedId > 0L)

            val parser = Agent1SmsParser(engine, realRepo)
            val outcome = parser.parse(rawSms)
            assertNotNull("parser should return an outcome even on conflict", outcome)
            assertEquals(seedId, outcome!!.parsed.id)
            assertEquals(rawSms.id, outcome.parsed.rawSmsId)
            assertEquals(a1Json, outcome.response)
            assertNotNull(outcome.prompt)
        } finally {
            db.close()
        }
    }

    @Test
    fun `mocked repo returning -1 from insert triggers re-fetch`() = runBlocking {
        val engine = mockk<GemmaInferenceEngine>()
        every { engine.state } returns MutableStateFlow(InferenceState.Ready("GPU"))
        val a1Json = """{"kind":"TRANSACTION","amountPaise":10000,"currency":"INR","direction":"DEBIT","txnAtMillis":null,"channel":"UPI","sourceKeyHint":null,"merchantRaw":"Acme","cardLast4Hint":null,"accountLast4Hint":null,"referenceNo":null,"confidence":0.95}"""
        coEvery {
            engine.generatePredictionTracking(any<String>(), any<String>(), anyNullable<Int>())
        } returns flowOf(a1Json)

        val repo = mockk<ParsedSmsRepository>()
        coEvery { repo.insert(any()) } returns -1L
        val existing = ParsedSms(
            id = 99L,
            rawSmsId = rawSms.id,
            parsedAt = 1_700_000_000_000L,
            kind = ParsedSmsKind.TRANSACTION.name,
            amountPaise = 10000L,
            currency = "INR",
            direction = "DEBIT",
            channel = "UPI",
            merchantRaw = "Acme",
            a1Confidence = 0.95f,
            a1RawJson = a1Json,
        )
        coEvery { repo.getByRawSms(rawSms.id) } returns existing

        val parser = Agent1SmsParser(engine, repo)
        val outcome = parser.parse(rawSms)
        assertNotNull(outcome)
        assertEquals(99L, outcome!!.parsed.id)
        coVerify(exactly = 1) { repo.getByRawSms(rawSms.id) }
    }
}
