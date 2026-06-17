package com.spendai.app.domain.agent

import com.spendai.app.data.local.entity.ParsedSmsKind
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.local.entity.SmsStatus
import com.spendai.app.data.repository.ParsedSmsRepository
import com.spendai.app.domain.model.MerchantNormalizer
import com.spendai.app.inference.GemmaInferenceEngine
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locked-down regression net for the multi-agent pipeline.
 *
 * The corpus file at `app/src/test/resources/sms_corpus.json` is the
 * single source of truth: each entry has a sender, body, and the
 * expected Agent 1 output. This test:
 *  1. Loads the corpus.
 *  2. Builds the JSON the model would return for each entry, using
 *     the `expectedXxx` fields. The JSON is shaped exactly like the
 *     model's actual output (Gemma 4 E2B sometimes wraps it in code
 *     fences, so we mix in one such case to lock down parser tolerance).
 *  3. Stubs [GemmaInferenceEngine.generatePrediction] to return that
 *     JSON.
 *  4. Calls [Agent1SmsParser.parse] and asserts the inserted
 *     [com.spendai.app.data.local.entity.ParsedSms] matches the
 *     expectations.
 *
 * This catches contract regressions, JSON parser regressions, and
 * `toEntity` bugs without burning device time on the real LLM.
 */
class SmsCorpusTest {

    @Serializable
    data class Corpus(
        val entries: List<CorpusEntry> = emptyList(),
    )

    @Serializable
    data class CorpusEntry(
        val id: String,
        val sender: String,
        val body: String,
        val expectedKind: String,
        val expectedAmountPaise: Long? = null,
        val expectedDirection: String? = null,
        val expectedChannel: String? = null,
        val expectedMerchantRaw: String? = null,
        val expectedNormalizedMerchant: String? = null,
    )

    private fun loadCorpus(): Corpus {
        val text = File("src/test/resources/sms_corpus.json").readText()
        return Json { ignoreUnknownKeys = true }.decodeFromString(Corpus.serializer(), text)
    }

    private fun expectedA1Json(entry: CorpusEntry, wrapInFence: Boolean): String {
        val obj = buildString {
            append("{\"kind\":\"")
            append(entry.expectedKind)
            append("\",")
            append("\"amountPaise\":")
            append(entry.expectedAmountPaise?.toString() ?: "null")
            append(",\"currency\":\"")
            append(if (entry.expectedKind == "TRANSACTION") "INR" else "")
            append("\",")
            append("\"direction\":")
            if (entry.expectedDirection != null) {
                append("\"").append(entry.expectedDirection).append("\"")
            } else append("null")
            append(",\"txnAtMillis\":null,")
            append("\"channel\":")
            if (entry.expectedChannel != null) {
                append("\"").append(entry.expectedChannel).append("\"")
            } else append("null")
            append(",\"sourceKeyHint\":null,")
            append("\"merchantRaw\":")
            if (entry.expectedMerchantRaw != null) {
                append("\"").append(entry.expectedMerchantRaw).append("\"")
            } else append("null")
            append(",\"cardLast4Hint\":null,\"accountLast4Hint\":null,")
            append("\"referenceNo\":null,")
            append("\"confidence\":0.9}")
        }
        return if (wrapInFence) "```json\n$obj\n```" else obj
    }

    @Test
    fun `corpus drives Agent 1 to expected ParsedSms rows`() = runBlocking {
        val corpus = loadCorpus()
        assertTrue("corpus must have entries", corpus.entries.isNotEmpty())

        val captured = mutableListOf<com.spendai.app.data.local.entity.ParsedSms>()
        val repo = mockk<ParsedSmsRepository>()
        coEvery { repo.insert(any()) } answers {
            val row = firstArg<com.spendai.app.data.local.entity.ParsedSms>()
            captured += row
            (captured.size).toLong()
        }
        val engine = mockk<GemmaInferenceEngine>()
        val parser = Agent1SmsParser(engine, repo)

        for ((idx, entry) in corpus.entries.withIndex()) {
            captured.clear()
            val raw = RawSmsMessage(
                id = (idx + 1).toLong(),
                senderAddress = entry.sender,
                msgBody = entry.body,
                timestamp = 1_700_000_000_000L + idx * 60_000L,
                status = SmsStatus.UNPARSED,
            )
            val canned = expectedA1Json(entry, wrapInFence = (idx % 3 == 0))
            coEvery { engine.state } returns MutableStateFlow(com.spendai.app.inference.InferenceState.Ready("NPU"))
        coEvery { engine.generatePrediction(any()) } returns canned
        coEvery { engine.generatePredictionTracking(any<String>(), any<String>(), anyNullable<Int>()) } returns
            kotlinx.coroutines.flow.flowOf(canned)

            val parsed = parser.parse(raw)
            assertNotNull("parser returned null for ${entry.id}", parsed)
            assertEquals(entry.expectedKind, parsed!!.parsed.kind)
            if (entry.expectedKind == "TRANSACTION") {
                assertEquals(entry.expectedAmountPaise, parsed.parsed.amountPaise)
                assertEquals(entry.expectedDirection, parsed.parsed.direction)
                assertEquals(entry.expectedChannel, parsed.parsed.channel)
                assertEquals(entry.expectedMerchantRaw, parsed.parsed.merchantRaw)
                if (entry.expectedNormalizedMerchant != null) {
                    assertEquals(
                        entry.expectedNormalizedMerchant,
                        MerchantNormalizer.normalize(parsed.parsed.merchantRaw),
                    )
                }
            } else {
                assertEquals(ParsedSmsKind.IGNORE.name, parsed.parsed.kind)
                assertNull(parsed.parsed.amountPaise)
            }
        }
    }


    @Test
    fun `corpus includes ignore cases`() {
        val corpus = loadCorpus()
        val ignores = corpus.entries.filter { it.expectedKind == "IGNORE" }
        assertTrue("corpus must include >=2 ignore cases", ignores.size >= 2)
    }
}
