package com.spendai.app.domain.agent

import com.spendai.app.data.local.entity.SourceInstrumentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class A2ContractTest {

    @Test fun `parses existing-source response`() {
        val raw = """
            {
              "source":  { "kind": "existing", "sourceId": 5, "confidence": 0.95 },
              "account": { "kind": "existing", "accountId": 12, "confidence": 0.9 },
              "merchant":{ "kind": "existing", "merchantId": 42, "confidence": 0.9 },
              "possibleLink": null,
              "a2Confidence": 0.9
            }
        """.trimIndent()
        val parsed = AgentJsonParse.tryParse(raw, A2Contract.serializer())
        val s = parsed!!.source as SourceChoice.Existing
        assertEquals(5L, s.sourceId)
    }

    @Test fun `parses new-source response with discriminator`() {
        val raw = """
            {
              "source":  { "kind": "new", "sourceKey": "Bank_3001", "deducedType": "CREDIT_CARD",
                           "suggestedBankName": "HDFC", "suggestedInstrumentType": "CARD",
                           "suggestedDisplayName": "HDFC Card", "confidence": 0.85 },
              "account": { "kind": "new", "instrumentType": "CARD", "issuer": "HDFC",
                           "maskedNumber": "XXXX1234", "currency": "INR", "confidence": 0.85 },
              "merchant":{ "kind": "new", "name": "Zomato", "normalizedName": "zomato",
                           "vpa": null, "confidence": 0.85 },
              "a2Confidence": 0.85
            }
        """.trimIndent()
        val parsed = AgentJsonParse.tryParse(raw, A2Contract.serializer())
        val s = parsed!!.source as SourceChoice.New
        assertEquals("Bank_3001", s.sourceKey)
        assertEquals("CREDIT_CARD", s.deducedType)
        assertEquals("HDFC", s.suggestedBankName)
        assertEquals(SourceInstrumentType.CARD, SourceInstrumentType.valueOf(s.suggestedInstrumentType))
        val a = parsed.account as AccountChoice.New
        assertEquals("XXXX1234", a.maskedNumber)
        val m = parsed.merchant as MerchantChoice.New
        assertEquals("zomato", m.normalizedName)
    }

    @Test fun `parses none-merchant response`() {
        val raw = """
            {
              "source":  { "kind": "existing", "sourceId": 1, "confidence": 0.9 },
              "account": { "kind": "existing", "accountId": 2, "confidence": 0.9 },
              "merchant":{ "kind": "none", "confidence": 0.7 },
              "a2Confidence": 0.9
            }
        """.trimIndent()
        val parsed = AgentJsonParse.tryParse(raw, A2Contract.serializer())
        assertTrue(parsed!!.merchant is MerchantChoice.None)
    }

    @Test fun `parses possibleLink`() {
        val raw = """
            {
              "source":  { "kind": "existing", "sourceId": 1, "confidence": 0.9 },
              "account": { "kind": "existing", "accountId": 2, "confidence": 0.9 },
              "merchant":{ "kind": "none", "confidence": 0.7 },
              "possibleLink": { "partnerParsedSmsId": 99, "linkType": "SELF_TRANSFER", "confidence": 0.8 },
              "a2Confidence": 0.9
            }
        """.trimIndent()
        val parsed = AgentJsonParse.tryParse(raw, A2Contract.serializer())
        assertEquals(99L, parsed!!.possibleLink!!.partnerParsedSmsId)
    }
}
