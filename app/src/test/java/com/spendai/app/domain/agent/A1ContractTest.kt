package com.spendai.app.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class A1ContractTest {

    @Test fun `parses transaction response`() {
        val raw = """
            {
              "kind": "TRANSACTION",
              "amountPaise": 123456,
              "currency": "INR",
              "direction": "DEBIT",
              "txnAtMillis": 1749559320000,
              "channel": "CARD",
              "sourceKeyHint": "Bank_HDFCCC",
              "merchantRaw": "ZOMATO",
              "cardLast4Hint": "1234",
              "accountLast4Hint": null,
              "referenceNo": null,
              "confidence": 0.96
            }
        """.trimIndent()
        val parsed = AgentJsonParse.tryParse(raw, A1Contract.serializer())
        assertNotNull(parsed)
        assertEquals("TRANSACTION", parsed!!.kind)
        assertEquals(123456L, parsed.amountPaise)
        assertEquals("DEBIT", parsed.direction)
        assertEquals(0.96f, parsed.confidence, 0.0001f)
    }

    @Test fun `parses ignore response`() {
        val raw = """
            {
              "kind": "IGNORE",
              "amountPaise": null, "currency": null, "direction": null,
              "txnAtMillis": null, "channel": null, "sourceKeyHint": null,
              "merchantRaw": null, "cardLast4Hint": null, "accountLast4Hint": null,
              "referenceNo": null, "confidence": 1.0
            }
        """.trimIndent()
        val parsed = AgentJsonParse.tryParse(raw, A1Contract.serializer())
        assertNotNull(parsed)
        assertEquals("IGNORE", parsed!!.kind)
        assertNull(parsed.amountPaise)
    }

    @Test fun `toEntity with IGNORE clears nullable fields`() {
        val c = A1Contract(kind = "IGNORE", confidence = 1.0f)
        val e = c.toEntity(rawSmsId = 1L, rawJson = "{}", parsedAt = 0L)
        assertNull(e.amountPaise)
        assertNull(e.direction)
    }

    @Test fun `toEntity with TRANSACTION preserves fields`() {
        val c = A1Contract(
            kind = "TRANSACTION",
            amountPaise = 9999L,
            currency = "INR",
            direction = "CREDIT",
            merchantRaw = "Acme",
            confidence = 0.88f,
        )
        val e = c.toEntity(rawSmsId = 7L, rawJson = "{}", parsedAt = 100L)
        assertEquals(9999L, e.amountPaise)
        assertEquals("CREDIT", e.direction)
        assertEquals("Acme", e.merchantRaw)
    }
}
