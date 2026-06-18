package com.spendai.app.domain.agent.insights

import android.util.Log
import com.spendai.app.domain.agent.AgentJsonParse
import com.spendai.app.inference.ChatMessage
import com.spendai.app.inference.GemmaInferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The model-as-judge verifier for the agentic insights
 * flow.
 *
 * ## Why a second model call
 *
 * The orchestrator's first attempt at a verifier was
 * regex-based: pull every "₹1,234" and "12 Jun" out of the
 * assistant's answer, check whether the empty tool result
 * contained them. That caught the original H&M fabrication
 * but missed the "₹4,100 / Myntra" case because:
 *
 *  - the regex never fired when the tool had returned any
 *    rows at all, and
 *  - even if it had, "Myntra" is a name not a number, and
 *    the regex did not look for names.
 *
 * A model call that takes the full (user question, SQL,
 * rows, answer) tuple can do that judgement: a total that
 * does not sum the rows, a merchant name that does not
 * appear in any cell, a ranking that inverts the data.
 * The cost is one extra model call per agent turn; the
 * benefit is that the user can trust any answer that
 * survives the check.
 *
 * ## Contract
 *
 * The verifier emits a single JSON object on the last line
 * of its response. [AgentJsonParse.extractFirstJsonObject]
 * locates it; [VerifierVerdict.serializer] decodes it. On
 * any failure (engine exception, malformed JSON, missing
 * fields) [FAIL_SAFE] is returned so the orchestrator
 * treats the answer as unverified and re-prompts rather
 * than silently trusting the agent.
 *
 * ## Latency
 *
 * Each call is one extra round trip to the configured
 * provider (currently Gemini at ~15-30s with the
 * 32K-context model). Three verifier rounds in the
 * worst-case 3-attempt loop add up to ~90s. v1 accepts the
 * latency; a smaller / faster judge model is a follow-up.
 */
class AnswerVerifier(
    private val engine: GemmaInferenceEngine,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Run a single grounding check. The call is non-blocking
     * but suspending; the orchestrator awaits the verdict
     * before deciding whether to accept the answer.
     */
    suspend fun verify(
        userQuestion: String,
        sql: String,
        rows: List<Map<String, JsonElement>>,
        agentAnswer: String,
    ): VerifierVerdict = withContext(Dispatchers.IO) {
        val messages = listOf(
            ChatMessage(ChatMessage.ROLE_USER, buildUserPrompt(userQuestion, sql, rows, agentAnswer)),
        )
        val responseText = try {
            engine.generateChatTracking(
                messages = messages,
                stepLabel = "agentic.verifier",
                systemInstructionOverride = SYSTEM_PROMPT,
            ).toList().joinToString("")
        } catch (t: Throwable) {
            Log.w(TAG, "Verifier model call failed: ${t.message}", t)
            return@withContext VerifierVerdict.FAIL_SAFE
        }
        parseVerdict(responseText)
    }

    /**
     * Build the user-facing prompt the judge model sees.
     * Kept compact: only the question, SQL, rows, and the
     * agent's answer. The system prompt owns the rules.
     */
    private fun buildUserPrompt(
        userQuestion: String,
        sql: String,
        rows: List<Map<String, JsonElement>>,
        agentAnswer: String,
    ): String {
        val rowsJson = renderRowsForJudge(rows)
        return buildString {
            appendLine("## User question")
            appendLine(userQuestion.trim())
            appendLine()
            appendLine("## SQL the agent ran")
            appendLine(sql.trim())
            appendLine()
            appendLine("## Tool result (rows the database returned)")
            appendLine(rowsJson)
            appendLine()
            appendLine("## Agent's answer")
            appendLine(agentAnswer.trim())
            appendLine()
            appendLine("## Your verdict")
            appendLine(
                "Reply with a single JSON object on the last line. No prose, no fences. " +
                    "Schema: {\"verdict\": \"grounded\" | \"fabricated\", \"unverifiedClaims\": [strings], " +
                    "\"evidence\": \"one-sentence summary\"}."
            )
        }
    }

    /**
     * Render the rows the judge will inspect. The format is
     * a compact JSON object so the model can quote it back
     * verbatim in the evidence field. We cap at
     * [MAX_VERIFIER_ROWS] so a runaway query does not
     * blow up the context window.
     */
    private fun renderRowsForJudge(rows: List<Map<String, JsonElement>>): String {
        if (rows.isEmpty()) return "(no rows)"
        val capped = rows.take(MAX_VERIFIER_ROWS)
        val truncated = rows.size > MAX_VERIFIER_ROWS
        val obj = buildJsonObject {
            put("rowCount", rows.size)
            if (truncated) put("note", "Only the first $MAX_VERIFIER_ROWS of ${rows.size} rows are shown.")
            put("rows", JsonPrimitive(capped.toCompactJsonString()))
        }
        return obj.toString()
    }

    private fun parseVerdict(raw: String): VerifierVerdict {
        val firstBlock = AgentJsonParse.extractFirstJsonObject(raw)
            ?: return VerifierVerdict.FAIL_SAFE
        return runCatching {
            com.spendai.app.domain.agent.AgentPrompt.JSON.decodeFromString(
                VerifierVerdict.serializer(),
                firstBlock,
            )
        }.getOrElse { t ->
            Log.w(TAG, "Verifier JSON did not parse: ${t.message}", t)
            VerifierVerdict.FAIL_SAFE
        }
    }

    companion object {
        private const val TAG = "AnswerVerifier"

        /**
         * Cap on rows forwarded to the judge. The orchestrator
         * already enforces a 200-row LIMIT, so 100 is
         * comfortably above any one turn's payload.
         */
        const val MAX_VERIFIER_ROWS: Int = 100

        /**
         * Strict, short system prompt. Four rules. One
         * output schema. No prose. The judge either says
         * "grounded" with empty unverifiedClaims or
         * "fabricated" with the specific offending claims.
         */
        const val SYSTEM_PROMPT: String = """You are a strict grounding verifier for a personal-finance AI. The agent ran a SQL query against the user's local SQLite database, got rows, and produced an answer. Your job is to check whether the answer's specific claims are supported by the rows.

Rules:

1. AMOUNTS. A rupee amount (₹1,234 / Rs. 1234 / 1234 rupees / 1234 INR) in the answer is supported when a row contains the value within 1% rounding, OR when the sum of a relevant column in the rows matches the amount. If the answer quotes a TOTAL that does not equal the sum of the rows over the same range, the total is fabricated.

2. NAMES. A merchant or category name in the answer is supported when any cell of any row contains the name (case-insensitive, punctuation-insensitive). If the answer names a merchant that does not appear in any row, that name is fabricated.

3. DATES. A specific date ("12 Jun", "12/06/2026", "12 June 2026") is supported when any cell in any row carries the same day-month-year, OR when a `txnAtMillis` value decodes to that date. If the answer cites a date that does not correspond to any row, that date is fabricated.

4. RANKINGS. "X is the biggest", "most went to X", "X came first" is supported when the named item matches the top/bottom of the relevant ordering over the rows. If the named item is not the top/bottom, the ranking is fabricated.

Hedging ("I think", "maybe", "you might want to check") and vague claims ("some merchants", "various categories") are always supported.

Output exactly one JSON object on the last line. No prose before it, no markdown fences, no trailing commas. Schema:

{
  "verdict": "grounded" or "fabricated",
  "unverifiedClaims": [ list of specific unsupported claims, each a short string the agent can re-check on its next turn ],
  "evidence": "one-sentence summary citing the row(s) that contradict each unverified claim"
}

If the answer has no specific claims (only prose like "you have no transactions on file"), the verdict is "grounded" with an empty unverifiedClaims list."""
    }
}

/**
 * Compact JSON serialisation for a [List] of [Map] of
 * [String] to [JsonElement]. Used by the verifier to
 * quote the rows the model should inspect. Lives in this
 * file so the verifier owns its input format.
 */
private fun List<Map<String, JsonElement>>.toCompactJsonString(): String {
    val sb = StringBuilder("[")
    forEachIndexed { rowIdx, row ->
        if (rowIdx > 0) sb.append(',')
        sb.append('{')
        var first = true
        for ((k, v) in row) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(escape(k)).append("\":")
            appendJsonValue(sb, v)
        }
        sb.append('}')
    }
    sb.append(']')
    return sb.toString()
}

private fun appendJsonValue(sb: StringBuilder, value: JsonElement) {
    when (value) {
        is JsonNull -> sb.append("null")
        is JsonPrimitive -> {
            if (value.isString) {
                sb.append('"').append(escape(value.content)).append('"')
            } else {
                sb.append(value.content)
            }
        }
        else -> {
            // Nested arrays/objects: fall back to a quoted
            // string so the judge still sees a stable JSON
            // shape. We don't expect nested data in the
            // current schema, but the contract is
            // forward-compatible.
            sb.append('"').append(escape(value.toString())).append('"')
        }
    }
}

private fun escape(s: String): String {
    val sb = StringBuilder(s.length + 4)
    for (c in s) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
    }
    return sb.toString()
}
