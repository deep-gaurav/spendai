package com.spendai.app.domain.agent.insights

import com.spendai.app.inference.GemmaInferenceEngine
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AnswerVerifier]. The verifier's job is
 * to parse a JSON verdict out of a model response. We
 * don't drive the full model call here (the test would
 * need a fake engine that streams a canned string into
 * `generateChatTracking`); instead we exercise the
 * [parseVerdict] path via a thin helper that wraps the
 * engine and the verifier.
 */
class AnswerVerifierTest {

    private fun parse(raw: String): VerifierVerdict {
        // The verifier's `parseVerdict` is private. We
        // exercise it indirectly by going through the
        // engine. The cleanest test seam is a fake
        // engine: we use the real engine but stub the
        // streaming path via the verifier's own
        // `verify` method, and a `runBlocking`-style
        // harness. Since the engine is hard to fake
        // without Robolectric we test the parsing logic
        // by routing through the JSON shape.
        val verifier = AnswerVerifier(engine = GemmaInferenceEngine())
        val m = verifier::class.java.getDeclaredMethod("parseVerdict", String::class.java)
        m.isAccessible = true
        return m.invoke(verifier, raw) as VerifierVerdict
    }

    @Test fun `grounded verdict with empty claims is pass`() {
        val v = parse("""{"verdict":"grounded","unverifiedClaims":[],"evidence":"all good"}""")
        assertFalse(v.isFabrication)
    }

    @Test fun `grounded verdict is a pass even with caveats`() {
        val v = parse("""{"verdict":"grounded","unverifiedClaims":["ignored"]}""")
        assertFalse(v.isFabrication)
    }

    @Test fun `fabricated verdict with claims is fabrication`() {
        val v = parse(
            """{"verdict":"fabricated","unverifiedClaims":["Myntra","₹4,100"],"evidence":"rows sum to ₹3,505"}""",
        )
        assertTrue(v.isFabrication)
        assertEquals(listOf("Myntra", "₹4,100"), v.unverifiedClaims)
    }

    @Test fun `fabricated verdict with empty claims is treated as pass`() {
        // The judge emitted "fabricated" but did not
        // list any specific claims. We treat this as a
        // pass because there is nothing concrete for
        // the model to fix on the re-prompt.
        val v = parse("""{"verdict":"fabricated","unverifiedClaims":[]}""")
        assertFalse(v.isFabrication)
    }

    @Test fun `case-insensitive verdict matching`() {
        val v1 = parse("""{"verdict":"GROUNDED","unverifiedClaims":[]}""")
        val v2 = parse("""{"verdict":"Fabricated","unverifiedClaims":["X"]}""")
        assertFalse(v1.isFabrication)
        assertTrue(v2.isFabrication)
    }

    @Test fun `malformed JSON falls back to FAIL_SAFE`() {
        val v = parse("not json at all")
        assertTrue(v.isFabrication)
        assertTrue(v.unverifiedClaims.first().contains("verifier call failed"))
    }

    @Test fun `missing verdict field falls back to FAIL_SAFE`() {
        val v = parse("""{"unverifiedClaims":["X"]}""")
        assertTrue(v.isFabrication)
    }

    @Test fun `empty input falls back to FAIL_SAFE`() {
        val v = parse("")
        assertTrue(v.isFabrication)
    }

    @Test fun `verifier system prompt mentions the four claim categories`() {
        val prompt = AnswerVerifier.SYSTEM_PROMPT
        assertTrue(prompt.contains("AMOUNTS"))
        assertTrue(prompt.contains("NAMES"))
        assertTrue(prompt.contains("DATES"))
        assertTrue(prompt.contains("RANKINGS"))
    }

    @Test fun `verifier system prompt requires JSON output`() {
        val prompt = AnswerVerifier.SYSTEM_PROMPT
        assertTrue(prompt.contains("JSON"))
        assertTrue(prompt.contains("verdict"))
        assertTrue(prompt.contains("unverifiedClaims"))
    }

    @Test fun `renderRowsForJudge caps at MAX_VERIFIER_ROWS`() {
        val verifier = AnswerVerifier(engine = GemmaInferenceEngine())
        val m = verifier::class.java.getDeclaredMethod("renderRowsForJudge", List::class.java)
        m.isAccessible = true
        val rows = (0 until (AnswerVerifier.MAX_VERIFIER_ROWS + 10)).map { i ->
            mapOf("id" to JsonPrimitive(i.toLong()))
        }
        val result = m.invoke(verifier, rows) as String
        // Truncation note should be present because we
        // requested more than MAX_VERIFIER_ROWS rows.
        assertTrue(result.contains("note"))
        assertTrue(result.contains("Only the first"))
    }
}
