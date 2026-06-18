package com.spendai.app.domain.agent.insights

/**
 * One entry in the agentic insights debug log. The
 * orchestrator appends a [AgenticDebugEntry] every time it
 * crosses a meaningful boundary: a system prompt, a user
 * turn, a model call, a tool call, a tool result, an error.
 *
 * The debug log is owned by the orchestrator (a singleton on
 * `SpendAiApp`) and exposed as a [kotlinx.coroutines.flow.StateFlow]
 * so the chat screen can render it under a "Debug" toggle
 * without coupling the orchestrator to the UI.
 *
 * The log is in-memory only; it is wiped on `clear()` along
 * with the conversation. A future "export debug" feature can
 * pipe the log to disk without changing the data shape.
 */
data class AgenticDebugEntry(
    val id: String,
    val timestamp: Long,
    val kind: AgenticDebugKind,
    val title: String,
    val content: String,
)

enum class AgenticDebugKind {
    /** The full system prompt, sent once at session start. */
    SYSTEM_PROMPT,

    /** A user turn from the user or from the verifier. */
    USER_TURN,

    /** The full conversation history that was sent to the model. */
    MODEL_REQUEST,

    /** The raw streamed text the model returned. */
    MODEL_RESPONSE,

    /** A parsed action the model produced. */
    PARSED_ACTION,

    /** A tool call the orchestrator dispatched. */
    TOOL_CALL,

    /** The raw JSON the tool produced. */
    TOOL_RESULT,

    /** A verifier flag that triggered a re-prompt. */
    VERIFIER_TRIGGERED,

    /** The verifier gave up after 3 attempts. */
    VERIFIER_GAVE_UP,
}
