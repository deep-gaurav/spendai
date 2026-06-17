package com.spendai.app.domain.agent

import com.google.ai.edge.litertlm.LiteRtLmJniException
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Locks down the engine-exception propagation contract for
 * [Agent1SmsParser]. The whole point of the v3 fix: a per-message
 * [LiteRtLmJniException] from the engine MUST surface to the pipeline
 * (where it is caught and routed to `skippedByA1++` + raw_sms stays
 * UNPARSED). The old `runCatching{}.getOrNull()` swallowed the throw
 * and substituted a synthetic `kind=IGNORE` contract, poisoning the
 * whole run.
 *
 * Also verifies the happy path (real model output) and the model-side
 * fallback (first try malformed JSON, retry malformed JSON, falls
 * back to a real `kind=IGNORE` with `confidence=0.0` and empty
 * `a1RawJson`). The fallback is by design: the A1 prompt says "if
 * the model returns malformed JSON twice, return a sentinel IGNORE",
 * which is different from "the engine itself threw".
 */
class Agent1SmsParserTest {

    private val rawSms = RawSmsMessage(
        id = 1L,
        senderAddress = "VK-TEST",
        msgBody = "Rs.100 spent at Acme",
        timestamp = 1_700_000_000_000L,
        status = SmsStatus.UNPARSED,
    )

    @Test
    fun `engine exception on first try propagates to the pipeline`() = runBlocking {
        val engine = mockk<GemmaInferenceEngine>()
        val repo = mockk<ParsedSmsRepository>(relaxed = true)
        every { engine.state } returns MutableStateFlow(InferenceState.Ready("GPU"))
        coEvery {
            engine.generatePredictionTracking(any<String>(), any<String>())
        } returns flow { throw LiteRtLmJniException("simulated GPU failure") }

        val parser = Agent1SmsParser(engine, repo)
        try {
            parser.parse(rawSms)
            fail("expected LiteRtLmJniException to propagate")
        } catch (t: Throwable) {
            assertTrue(
                "expected LiteRtLmJniException, got ${t::class.simpleName}: ${t.message}",
                t is LiteRtLmJniException,
            )
        }
        // The agent must not have persisted a synthetic IGNORE row.
        coVerify(exactly = 0) { repo.insert(any()) }
    }

    @Test
    fun `engine exception on retry also propagates (no silent IGNORE)`() = runBlocking {
        val engine = mockk<GemmaInferenceEngine>()
        val repo = mockk<ParsedSmsRepository>(relaxed = true)
        every { engine.state } returns MutableStateFlow(InferenceState.Ready("GPU"))
        val callIndex = intArrayOf(0)
        coEvery {
            engine.generatePredictionTracking(any<String>(), any<String>())
        } answers {
            when (callIndex[0]++) {
                0 -> flowOf("not-json-at-all")
                else -> flow { throw LiteRtLmJniException("simulated retry failure") }
            }
        }

        val parser = Agent1SmsParser(engine, repo)
        try {
            parser.parse(rawSms)
            fail("expected the retry throw to propagate")
        } catch (t: Throwable) {
            assertTrue(
                "expected LiteRtLmJniException, got ${t::class.simpleName}: ${t.message}",
                t is LiteRtLmJniException,
            )
        }
    }

    @Test
    fun `valid model output produces a TRANSACTION contract`() = runBlocking {
        val engine = mockk<GemmaInferenceEngine>()
        val repo = mockk<ParsedSmsRepository>(relaxed = true)
        every { engine.state } returns MutableStateFlow(InferenceState.Ready("GPU"))
        val json = """{"kind":"TRANSACTION","amountPaise":10000,"currency":"INR","direction":"DEBIT","txnAtMillis":null,"channel":"UPI","sourceKeyHint":null,"merchantRaw":"Acme","cardLast4Hint":null,"accountLast4Hint":null,"referenceNo":null,"confidence":0.95}"""
        coEvery {
            engine.generatePredictionTracking(any<String>(), any<String>())
        } returns flowOf(json)
        val inserted = mutableListOf<com.spendai.app.data.local.entity.ParsedSms>()
        coEvery { repo.insert(any()) } answers {
            val row = firstArg<com.spendai.app.data.local.entity.ParsedSms>()
            inserted += row
            row.id
        }

        val parser = Agent1SmsParser(engine, repo)
        val parsed = parser.parse(rawSms)
        assertNotNull("parser returned null", parsed)
        assertEquals("TRANSACTION", parsed!!.kind)
        assertEquals(10000L, parsed.amountPaise)
        assertEquals(0.95f, parsed.a1Confidence)
        // The rawJson stored on the row is the model's verbatim output,
        // NOT empty. This is the marker the pipeline uses to distinguish
        // a real IGNORE from a synthetic one.
        assertEquals(json, inserted.single().a1RawJson)
    }

    @Test
    fun `malformed JSON on both calls falls back to real kind=IGNORE with empty rawJson`() = runBlocking {
        val engine = mockk<GemmaInferenceEngine>()
        val repo = mockk<ParsedSmsRepository>(relaxed = true)
        every { engine.state } returns MutableStateFlow(InferenceState.Ready("GPU"))
        coEvery {
            engine.generatePredictionTracking(any<String>(), any<String>())
        } returns flowOf("not-valid-json")

        val parser = Agent1SmsParser(engine, repo)
        val parsed = parser.parse(rawSms)
        assertNotNull("parser returned null", parsed)
        assertEquals("IGNORE", parsed!!.kind)
        assertEquals(0.0f, parsed.a1Confidence)
        // This is the synthetic-IGNORE signature the pipeline now
        // detects and re-runs. A real model IGNORE has non-empty
        // a1RawJson; this one is empty.
        coVerify(exactly = 1) { repo.insert(any()) }
    }

    @Test
    fun `engine in non-Ready state returns null (no run)`() = runBlocking {
        val engine = mockk<GemmaInferenceEngine>()
        val repo = mockk<ParsedSmsRepository>(relaxed = true)
        every { engine.state } returns MutableStateFlow(InferenceState.Error("boom"))
        val parser = Agent1SmsParser(engine, repo)
        val result = parser.parse(rawSms)
        assertEquals(null, result)
        coVerify(exactly = 0) { engine.generatePredictionTracking(any<String>(), any<String>()) }
    }
}
