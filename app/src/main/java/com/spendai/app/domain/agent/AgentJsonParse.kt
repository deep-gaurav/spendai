package com.spendai.app.domain.agent

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lenient JSON parser for the agents' outputs.
 *
 * ## Failure modes observed in the wild
 *
 * Gemma 4 E2B / Gemma 4 31B occasionally:
 *  - wraps JSON in ```json ... ``` fences
 *  - adds a one-sentence preamble ("Sure, here is the JSON:")
 *  - drops a comma or leaves a trailing one
 *  - returns a top-level array when the schema wants an object
 *
 * OpenAI-compatible reasoning models (gpt-oss:120b, deepseek-r1,
 * qwen3) additionally:
 *  - emit a long `<think>...</think>` block BEFORE the answer; if
 *    the parser scans for the first `{` it finds the opening
 *    brace of the think-block, not the action.
 *  - pre-plan multiple actions in a single turn: a tool call
 *    followed by an `answer` action that should fire AFTER the
 *    tool result. The first-block parser sees only the tool call
 *    and loops, while the answer the model wanted to emit is
 *    sitting in the next balanced object.
 *  - return an empty stream (or stop after a stray "...") when
 *    asked to continue after a tool result, which the original
 *    parser surfaces as "no JSON found" and the orchestrator
 *    bails on.
 *
 * [tryParse] / [tryParseAny] tolerate the first two classes by
 * stripping thinking blocks and trying every balanced JSON
 * object in the response. The orchestrator's retry loop
 * (see [com.spendai.app.domain.agent.insights.AgenticInsightsAgent])
 * covers the third.
 */
object AgentJsonParse {

    /**
     * The [Json] instance used by the agents. Configured lenient:
     * unknown keys are dropped, trailing commas are forgiven,
     * `null` literals coerce to default values.
     */
    private val JSON: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Try to parse the model output as the given contract. Returns
     * the FIRST balanced JSON object that deserialises cleanly.
     * Use [tryParseAny] when you want to consider all blocks.
     */
    fun <T> tryParse(
        raw: String,
        serializer: KSerializer<T>,
    ): T? = tryParseFirst(raw, serializer)

    /**
     * Try every balanced JSON object in the response, in order,
     * and return the first that deserialises cleanly. Use this
     * for orchestrators that want to be tolerant of reasoning
     * models which pre-plan multiple actions in one turn.
     */
    fun <T> tryParseAny(
        raw: String,
        serializer: KSerializer<T>,
    ): T? {
        val blocks = extractAllJsonObjects(stripThinking(raw))
        for (block in blocks) {
            val parsed = runCatching { JSON.decodeFromString(serializer, block) }.getOrNull()
            if (parsed != null) return parsed
        }
        // Fall back to array-wrapping for legacy responses.
        val array = extractFirstJsonArray(stripThinking(raw))
        if (array != null) {
            val wrapped = "{\"v\":$array}"
            return runCatching { JSON.decodeFromString(serializer, wrapped) }.getOrNull()
        }
        return null
    }

    /**
     * Like [tryParseAny] but additionally prefers an answer-shaped
     * payload (one with an `action` discriminator equal to
     * `answer`) when more than one block parses. Reasoning models
     * often emit a tool call first, then the answer they would
     * have given after the tool result. Returning the answer here
     * skips a round-trip when the model's "final intent" is to
     * stop the loop.
     */
    fun <T> tryParsePreferringAnswer(
        raw: String,
        answerAction: String,
        serializer: KSerializer<T>,
    ): T? {
        val blocks = extractAllJsonObjects(stripThinking(raw))
        val decoded = ArrayList<T>(blocks.size)
        for (block in blocks) {
            runCatching { JSON.decodeFromString(serializer, block) }
                .getOrNull()?.let { decoded += it }
        }
        if (decoded.isEmpty()) return null
        // Prefer the LAST parsed block whose JSON contains
        // `"action":"<answerAction>"`. Reasoning models tend
        // to put the final answer at the end of the turn; the
        // earlier blocks are usually the tool calls they were
        // going to make.
        val answerIndex = blocks.indexOfLast { block ->
            runCatching {
                val obj = JSON.parseToJsonElement(block) as? JsonObject
                val action = obj?.get("action")?.jsonPrimitive?.contentOrNullSafe()
                action == answerAction
            }.getOrDefault(false)
        }
        return decoded.getOrNull(answerIndex.takeIf { it >= 0 } ?: 0)
    }

    /**
     * Try the FIRST balanced JSON object and return whatever it
     * deserialises to. Mirrors the legacy [tryParse] behaviour.
     */
    private fun <T> tryParseFirst(
        raw: String,
        serializer: KSerializer<T>,
    ): T? {
        val first = extractFirstJsonObject(stripThinking(raw))
        if (first == null) {
            val array = extractFirstJsonArray(stripThinking(raw))
            if (array != null) {
                val wrapped = "{\"v\":$array}"
                return runCatching { JSON.decodeFromString(serializer, wrapped) }.getOrNull()
            }
            return null
        }
        return runCatching { JSON.decodeFromString(serializer, first) }.getOrNull()
    }

    /**
     * Remove thinking blocks the reasoning model might emit. Two
     * patterns are stripped: the explicit `<think>...</think>`
     * fence (qwen, deepseek) and a bare `<thinking>...</thinking>`
     * block. Anything between the closing fence and the next
     * opening fence is preserved.
     */
    fun stripThinking(raw: String): String {
        if (raw.isEmpty()) return raw
        var result = raw
        // Strip <think>...</think> and <thinking>...</thinking>
        // case-insensitively. The first capture group inside is
        // the actual thinking text; we throw it away.
        result = Regex(
            "(?is)<\\s*think(?:ing)?\\s*>(.*?)<\\s*/\\s*think(?:ing)?\\s*>",
        ).replace(result, "")
        return result
    }

    /**
     * Locate the first balanced `{ ... }` block in [raw]. Skips over
     * code fences and prose. Returns null if none is found.
     */
    fun extractFirstJsonObject(raw: String): String? {
        val cleaned = raw.replace("```json", "").replace("```", "")
        return findBalanced(cleaned, open = '{', close = '}')
    }

    /**
     * Return EVERY balanced `{ ... }` block in [raw], in source
     * order. Empty when the response has no JSON. Used by
     * [tryParseAny] and [tryParsePreferringAnswer] to look past
     * pre-planned tool calls for the model's final answer.
     */
    fun extractAllJsonObjects(raw: String): List<String> {
        val cleaned = raw.replace("```json", "").replace("```", "")
        return findAllBalanced(cleaned, open = '{', close = '}')
    }

    private fun findBalanced(s: String, open: Char, close: Char): String? {
        val start = s.indexOf(open)
        if (start < 0) return null
        val (depth, inString, escape) = Triple(0, false, false)
        var d = depth
        var inS = inString
        var esc = escape
        for (i in start until s.length) {
            val c = s[i]
            if (esc) { esc = false; continue }
            when (c) {
                '\\' -> if (inS) esc = true
                '"' -> inS = !inS
                open -> if (!inS) d++
                close -> if (!inS) {
                    d--
                    if (d == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * Walk the string and emit every balanced object in source
     * order. The scan restarts inside each emitted object so
     * nested arrays / objects are skipped, not double-counted.
     */
    private fun findAllBalanced(s: String, open: Char, close: Char): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val start = s.indexOf(open, i)
            if (start < 0) break
            var depth = 0
            var inString = false
            var escape = false
            var j = start
            while (j < s.length) {
                val c = s[j]
                if (escape) { escape = false; j++; continue }
                when (c) {
                    '\\' -> if (inString) { escape = true }
                    '"' -> inString = !inString
                    open -> if (!inString) depth++
                    close -> if (!inString) {
                        depth--
                        if (depth == 0) {
                            out += s.substring(start, j + 1)
                            i = j + 1
                            break
                        }
                    }
                }
                j++
            }
            if (j >= s.length) {
                // Unbalanced trailing brace: stop here.
                break
            }
        }
        return out
    }

    private fun extractFirstJsonArray(raw: String): String? {
        val start = raw.indexOf('[')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (escape) { escape = false; continue }
            when (c) {
                '\\' -> if (inString) escape = true
                '"' -> inString = !inString
                '[' -> if (!inString) depth++
                ']' -> if (!inString) {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * Safe accessor: [kotlinx.serialization.json.JsonPrimitive.content]
     * throws on non-string primitives, so we wrap and fall back to
     * null. Used by [tryParsePreferringAnswer] to look at the
     * `action` field of a candidate JSON block.
     */
    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()
}
