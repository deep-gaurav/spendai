package com.spendai.app.domain.agent

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Lenient JSON parser for the agents' outputs.
 *
 * Gemma 4 E2B occasionally:
 *  - wraps JSON in ```json ... ``` fences
 *  - adds a one-sentence preamble ("Sure, here is the JSON:")
 *  - drops a comma or leaves a trailing one
 *  - returns a top-level array when the schema wants an object
 *
 * [extractFirstJsonObject] locates the first balanced `{ ... }` block
 * in the model's output and returns it. The kotlinx [Json] instance
 * is configured with [JsonBuilder.isLenient] and
 * [JsonBuilder.ignoreUnknownKeys] so a sloppy payload still parses.
 */
object AgentJsonParse {

    /**
     * Try to parse the model output as the given contract. Returns
     * null on any failure — callers decide whether to retry.
     */
    fun <T> tryParse(
        raw: String,
        serializer: KSerializer<T>,
    ): T? = runCatching {
        val first = extractFirstJsonObject(raw)
        val firstBlock = first
        if (firstBlock == null) {
            // Maybe the model returned a top-level array; try wrapping.
            val array = extractFirstJsonArray(raw)
            if (array != null) {
                val wrapped = "{\"v\":$array}"
                AgentPrompt.JSON.decodeFromString(serializer, wrapped)
            } else {
                null
            }
        } else {
            AgentPrompt.JSON.decodeFromString(serializer, firstBlock)
        }
    }.getOrNull()

    /**
     * Locate the first balanced `{ ... }` block in [raw]. Skips over
     * code fences and prose. Returns null if none is found.
     */
    fun extractFirstJsonObject(raw: String): String? {
        // Strip markdown code fences.
        val cleaned = raw
            .replace("```json", "")
            .replace("```", "")
        val start = cleaned.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until cleaned.length) {
            val c = cleaned[i]
            if (escape) { escape = false; continue }
            when (c) {
                '\\' -> if (inString) escape = true
                '"' -> inString = !inString
                '{' -> if (!inString) depth++
                '}' -> if (!inString) {
                    depth--
                    if (depth == 0) return cleaned.substring(start, i + 1)
                }
            }
        }
        return null
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
}
