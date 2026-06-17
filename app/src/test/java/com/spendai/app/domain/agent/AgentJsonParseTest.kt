package com.spendai.app.domain.agent

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
