package com.spendai.app.domain.agent

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Serializable
data class Sample(val kind: String, val amount: Int? = null, val confidence: Float = 0f)

class AgentJsonParseTest {

    @Test fun `parses clean json`() {
        val out = AgentJsonParse.tryParse(
            "{\"kind\":\"TRANSACTION\",\"amount\":1234,\"confidence\":0.9}",
            Sample.serializer(),
        )
        assertNotNull(out)
        assertEquals("TRANSACTION", out!!.kind)
        assertEquals(1234, out.amount)
        assertEquals(0.9f, out.confidence, 0.0001f)
    }

    @Test fun `strips code fences`() {
        val out = AgentJsonParse.tryParse(
            "```json\n{\"kind\":\"IGNORE\",\"confidence\":1.0}\n```",
            Sample.serializer(),
        )
        assertNotNull(out)
        assertEquals("IGNORE", out!!.kind)
    }

    @Test fun `handles preamble prose`() {
        val out = AgentJsonParse.tryParse(
            "Sure, here is the JSON you asked for:\n{\"kind\":\"TRANSACTION\",\"confidence\":0.7}",
            Sample.serializer(),
        )
        assertNotNull(out)
        assertEquals("TRANSACTION", out!!.kind)
    }

    @Test fun `returns null on no json`() {
        val out = AgentJsonParse.tryParse("no json here", Sample.serializer())
        assertNull(out)
    }

    @Test fun `returns null on mismatched braces`() {
        val out = AgentJsonParse.tryParse("{\"kind\": \"X\"", Sample.serializer())
        assertNull(out)
    }

    @Test fun `extractFirstJsonObject returns null for empty`() {
        assertNull(AgentJsonParse.extractFirstJsonObject(""))
    }

    @Test fun `extractFirstJsonObject finds the first block`() {
        val out = AgentJsonParse.extractFirstJsonObject("hi {a:1} rest {b:2}")
        assertEquals("{a:1}", out)
    }

    @Test fun `strips thinking blocks before scanning for JSON`() {
        // Use unicode escapes so the test source itself does
        // not contain literal '<' or '>' (Kotlin backtick
        // identifiers reject those characters). The escape
        // resolves to the same string the model would emit:
        //   <think>...</think>
        val out = AgentJsonParse.stripThinking(
            "\u003cthink>The user wants a breakdown. I will query.\u003c/think>" +
                "{\"kind\":\"TRANSACTION\",\"confidence\":0.9}"
        )
        assertFalse(
            "think block should be gone, but was: " + out,
            out.contains("think>"),
        )
        assertTrue("JSON should survive", out.contains("TRANSACTION"))
    }

    @Test fun `tryParseAny returns the first parseable block in a multi-action turn`() {
        val raw = "some prose " +
            "{\"kind\":\"FIRST\",\"confidence\":0.5} " +
            "more prose " +
            "{\"kind\":\"SECOND\",\"confidence\":0.9}"
        val out = AgentJsonParse.tryParseAny(raw, Sample.serializer())
        assertNotNull(out)
        // The first block is the one the legacy parser would have picked.
        assertEquals("FIRST", out!!.kind)
    }

    @Test fun `tryParsePreferringAnswer picks the LAST answer-shaped block`() {
        val raw = "{\"kind\":\"FIRST\",\"confidence\":0.5} " +
            "{\"action\":\"answer\",\"kind\":\"ANSWER\",\"confidence\":0.9}"
        val out = AgentJsonParse.tryParsePreferringAnswer(
            raw = raw,
            answerAction = "answer",
            serializer = Sample.serializer(),
        )
        assertNotNull(out)
        // The "answer" block (last in the stream) wins, even
        // though it parsed second.
        assertEquals("ANSWER", out!!.kind)
    }

    @Test fun `tryParsePreferringAnswer falls back to the first parseable block when no answer shape is present`() {
        val raw = "{\"kind\":\"A\",\"confidence\":0.5} " +
            "{\"kind\":\"B\",\"confidence\":0.9}"
        val out = AgentJsonParse.tryParsePreferringAnswer(
            raw = raw,
            answerAction = "answer",
            serializer = Sample.serializer(),
        )
        assertNotNull(out)
        assertEquals("A", out!!.kind)
    }
}
