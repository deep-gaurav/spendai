package com.spendai.app.domain.agent

import com.spendai.app.data.local.entity.ParsedSms
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.repository.ParsedSmsRepository
import com.spendai.app.inference.GemmaInferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import android.util.Log
import kotlinx.coroutines.withContext

/**
 * Agent 1: per-message SMS parser.
 *
 * Pure extraction. No DB access. Reads the raw SMS body and produces
 * a [ParsedSms] row. Idempotent: the worker calls this only when no
 * existing [ParsedSms] row is found for the [RawSmsMessage].
 *
 * On model failure (malformed JSON, nonsense output) the agent tries
 * one corrective retry. If that also fails it returns a
 * `kind=IGNORE` row so the worker can mark the SMS as IGNORED and
 * move on; the underlying raw_sms is preserved for re-running later.
 */
class Agent1SmsParser(
    private val engine: GemmaInferenceEngine,
    private val parsedSmsRepository: ParsedSmsRepository,
) {
    private companion object {
        const val TAG = "Agent1SmsParser"
        const val LOG_TRUNCATE_CHARS = 400
        fun truncate(s: String?): String {
            if (s == null) return "<null>"
            return if (s.length <= LOG_TRUNCATE_CHARS) s
            else s.take(LOG_TRUNCATE_CHARS) + "...[+${s.length - LOG_TRUNCATE_CHARS} chars]"
        }
    }


    /**
     * @return the persisted [ParsedSms] row, or null if the engine
     *   is not READY (in which case the worker should `Result.retry()`).
     */
    suspend fun parse(
        rawSms: RawSmsMessage,
    ): ParsedSms? = withContext(Dispatchers.IO) {
        if (engine.state.value !is com.spendai.app.inference.InferenceState.Ready) {
            return@withContext null
        }

        val systemPrompt = AgentPrompt.A1_SYSTEM_INSTRUCTION
        val userMessage = AgentPrompt.a1UserMessage(rawSms)

        // generatePredictionTracking publishes per-token progress into
        // InferenceState.Busy so the home card can show "Decoded 87
        // tokens (agent1.parse) · 23s" instead of a generic
        // "Working…". first() on the tracking flow gives us the
        // full joined text (each chunk is appended via emit(chunk)).
        Log.d(TAG, "A1 input SMS [id=${rawSms.id}, sender=${rawSms.senderAddress}, ts=${rawSms.timestamp}]: ${truncate(rawSms.msgBody)}")
        val fullPrompt = "$systemPrompt\n\n$userMessage"
        Log.d(TAG, "A1 prompt sent to model (${fullPrompt.length} chars): ${truncate(fullPrompt)}")
        // Direct engine call (no runCatching) so LiteRtLmJniException
        // propagates to the pipeline. The pipeline's try/catch around
        // agent1.parse() routes per-message inference failures to
        // IngestionProgress.MessageSkipped + skippedByA1++, leaving
        // the raw_sms row UNPARSED for a future run. Swallowing the
        // throw here used to substitute a synthetic kind=IGNORE
        // contract and mark the row IGNORED forever.
        val first = engine.generatePredictionTracking(
            prompt = fullPrompt,
            stepLabel = "agent1.parse",
        ).toList().joinToString("")
        Log.d(TAG, "A1 raw model response (${first.length} chars): ${truncate(first)}")
        val firstParsed = first.let { AgentJsonParse.tryParse(it, A1Contract.serializer()) }
        Log.d(TAG, "A1 first-try parse: ${if (firstParsed != null) "OK kind=${firstParsed.kind} conf=${firstParsed.confidence}" else "FAILED (will retry)"}")

        val contract = firstParsed ?: run {
            // Retry only on a JSON-parse failure. If the engine itself
            // threw we never reach this point (A2/A3 follow the same rule).
            val retry = engine.generatePredictionTracking(
                prompt = AgentPrompt.A1_CORRECTIVE_PROMPT,
                stepLabel = "agent1.parse.retry",
            ).toList().joinToString("")
            Log.d(TAG, "A1 retry raw model response (${retry.length} chars): ${truncate(retry)}")
            retry.let { AgentJsonParse.tryParse(it, A1Contract.serializer()) }
        } ?: A1Contract(kind = "IGNORE", confidence = 0f)
        Log.d(TAG, "A1 final contract: kind=${contract.kind}, confidence=${contract.confidence}")

        val row = contract.toEntity(
            rawSmsId = rawSms.id,
            rawJson = first,
            parsedAt = System.currentTimeMillis(),
        )
        val id = parsedSmsRepository.insert(row)
        row.copy(id = id)
    }
}
