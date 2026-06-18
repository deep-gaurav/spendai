package com.spendai.app.domain.agent

import com.spendai.app.data.local.entity.ParsedSms

/**
 * Agent 1's per-message result, augmented with the prompt and
 * raw model response so the pipeline can audit-log them.
 *
 * A1 throws on engine / parse failure (the pipeline catches and
 * routes to `skippedByA1++`); it returns `null` only when the
 * engine itself is not READY (the worker uses that to
 * `Result.retry()`).
 */
data class A1Outcome(
    val parsed: ParsedSms,
    val prompt: String,
    val response: String,
)

/**
 * Agent 2's per-message result, augmented with the prompt and
 * raw model response.
 */
data class A2Outcome(
    val transactionId: Long,
    val prompt: String,
    val response: String,
    val a2Confidence: Float,
    val isDuplicate: Boolean = false,
)

/**
 * Thrown by [Agent2EntityResolver.resolveAndCommit] when both the
 * first attempt and the corrective retry fail to produce parseable
 * JSON (or the engine itself throws). The exception always carries
 * the prompt that was sent to the model and any partial response
 * that came back, so the pipeline's audit row is never blank.
 *
 * Without this carrier, an A2 failure would lose both the prompt
 * (constructed just before the engine call) and any response text
 * the model did emit before timing out / truncating, leaving the
 * debug pane useless.
 */
class A2FailureException(
    val prompt: String,
    val response: String?,
    cause: Throwable,
) : RuntimeException(cause.message ?: cause.javaClass.simpleName, cause)
