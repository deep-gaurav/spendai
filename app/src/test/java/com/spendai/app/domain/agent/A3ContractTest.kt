package com.spendai.app.domain.agent

import com.spendai.app.data.local.entity.TransactionDirection
import com.spendai.app.data.local.entity.TransactionLinkType
import com.spendai.app.data.local.entity.TransactionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class A3ContractTest {

    @Test fun `parses a commit batch`() {
        val raw = """
            {
              "commits": [
                {
                  "parsedSmsId": 12,
                  "finalTransaction": {
                    "accountId": 3, "merchantId": 42,
                    "rawSmsId": 17, "parsedSmsId": 12,
                    "amountPaise": 12345, "currency": "INR",
                    "direction": "DEBIT", "txnAtMillis": 1749559320000,
                    "channel": "UPI", "referenceNo": null,
                    "status": "CONFIRMED", "notes": null
                  },
                  "confidence": 0.92,
                  "linksToCreate": [],
                  "needsReview": false
                }
              ]
            }
        """.trimIndent()
        val parsed = AgentJsonParse.tryParse(raw, A3Contract.serializer())
        assertEquals(1, parsed!!.commits.size)
        val c = parsed.commits[0].toCommit()
        assertEquals(12L, c.parsedSmsId)
        assertEquals(0.92f, c.confidence, 0.0001f)
        assertEquals(12345L, c.finalTransaction.amountPaise)
        assertEquals(TransactionDirection.DEBIT, c.finalTransaction.direction)
        assertEquals(TransactionStatus.CONFIRMED, c.finalTransaction.status)
        assertTrue(c.linksToCreate.isEmpty())
        assertEquals(false, c.needsReview)
    }

    @Test fun `parses a commit with a self-transfer link`() {
        val raw = """
            {
              "commits": [
                {
                  "parsedSmsId": 11,
                  "finalTransaction": {
                    "accountId": 3, "merchantId": null,
                    "rawSmsId": 16, "parsedSmsId": 11,
                    "amountPaise": 50000, "currency": "INR",
                    "direction": "CREDIT", "txnAtMillis": 1749559320000,
                    "channel": "UPI", "referenceNo": null,
                    "status": "CONFIRMED", "notes": null
                  },
                  "confidence": 0.9,
                  "linksToCreate": [
                    { "partnerParsedSmsId": 10, "linkType": "SELF_TRANSFER", "confidence": 0.85 }
                  ],
                  "needsReview": false
                }
              ]
            }
        """.trimIndent()
        val parsed = AgentJsonParse.tryParse(raw, A3Contract.serializer())
        val c = parsed!!.commits[0].toCommit()
        assertEquals(1, c.linksToCreate.size)
        assertEquals(10L, c.linksToCreate[0].partnerParsedSmsId)
        assertEquals(TransactionLinkType.SELF_TRANSFER, c.linksToCreate[0].linkType)
    }
}
