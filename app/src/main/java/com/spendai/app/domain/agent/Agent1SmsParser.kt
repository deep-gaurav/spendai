package com.spendai.app.domain.agent

import android.util.Log
import com.spendai.app.data.local.entity.ParsedSms
import com.spendai.app.data.local.entity.RawSmsMessage
import com.spendai.app.data.repository.ParsedSmsRepository
import com.spendai.app.inference.GemmaInferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
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
 *
 * The returned [A1Outcome] includes the prompt and raw model
 * response so the pipeline can write them to the audit log.
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
     * @return the [A1Outcome] (parsed row + prompt + raw response),
     *   or null if the engine is not READY (in which case the worker
     *   should `Result.retry()`).
     *
     * @throws Throwable on engine exception (caller routes to
     *   `skippedByA1++`).
     */
    suspend fun parse(
        rawSms: RawSmsMessage,
    ): A1Outcome? = withContext(Dispatchers.IO) {
        if (engine.state.value !is com.spendai.app.inference.InferenceState.Ready) {
            return@withContext null
        }

        val systemPrompt = AgentPrompt.A1_SYSTEM_INSTRUCTION
        val userMessage = AgentPrompt.a1UserMessage(rawSms)
        val fullPrompt = "$systemPrompt\n\n$userMessage"

        Log.d(TAG, "A1 input SMS [id=${rawSms.id}, sender=${rawSms.senderAddress}, ts=${rawSms.timestamp}]: ${truncate(rawSms.msgBody)}")
        Log.d(TAG, "A1 prompt sent to model (${fullPrompt.length} chars): ${truncate(fullPrompt)}")
        val first = engine.generatePredictionTracking(
            prompt = fullPrompt,
            stepLabel = "agent1.parse",
        ).toList().joinToString("")
        Log.d(TAG, "A1 raw model response (${first.length} chars): ${truncate(first)}")
        val firstParsed = first.let { AgentJsonParse.tryParse(it, A1Contract.serializer()) }
        Log.d(TAG, "A1 first-try parse: ${if (firstParsed != null) "OK kind=${firstParsed.kind} conf=${firstParsed.confidence}" else "FAILED (will retry)"}")

        val contract = firstParsed ?: run {
            // Retry only on a JSON-parse failure. If the engine itself
            // threw we never reach this point (A2 follows the same rule).
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
        A1Outcome(
            parsed = row.copy(id = id),
            prompt = fullPrompt,
            response = first,
        )
    }
}
