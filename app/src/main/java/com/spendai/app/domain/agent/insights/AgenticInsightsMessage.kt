package com.spendai.app.domain.agent.insights

/**
 * The conversation model the UI binds to. Sealed so the
 * Compose layer can render each variant without nullable
 * fields, and so the orchestrator can do exhaustive
 * `when` checks when building the next engine turn.
 *
 * The orchestrator owns the list and rewrites the tail
 * message in place as the engine streams / the tool runs;
 * the UI subscribes to a `StateFlow<List<...>>` and just
 * recomposes.
 */
sealed interface AgenticInsightsMessage {

    /** A stable identity for Compose keying. */
    val id: String

    /**
     * Text the user typed and the orchestrator forwarded to
     * the model. The message is "complete" the moment it is
     * added — there is no streaming state for user input.
     */
    data class UserMessage(
        override val id: String,
        val text: String,
        val createdAt: Long,
    ) : AgenticInsightsMessage

    /**
     * The model's current turn. `streamedText` is the raw
     * text the engine has produced so far (used while the
     * stream is open). [parsed] is the structured action once
     * the stream completes and the JSON parses cleanly;
     * before that, [parsed] is null and the UI shows the
     * streamed text as a "thinking" bubble.
     */
    data class AssistantMessage(
        override val id: String,
        val streamedText: String,
        val parsed: AgenticAction?,
        val status: AssistantStatus,
    ) : AgenticInsightsMessage

    /**
     * A SQL tool call the orchestrator is about to run (or
     * has just run). Mirrors the assistant's intent so the UI
     * can show the user which query produced the next batch
     * of rows.
     */
    data class ToolCallMessage(
        override val id: String,
        val sql: String,
        val thought: String,
        val status: ToolCallStatus,
    ) : AgenticInsightsMessage

    /**
     * The rows a [ToolCallMessage] produced. If `error` is
     * non-null the tool call failed and [rows] is empty.
     * The orchestrator appends this message right after the
     * matching [ToolCallMessage] and before the next
     * assistant turn.
     *
     * [rows] is a serialisable snapshot of the cursor -
     * the full [SqlExecutor.QueryResult.rows] list, kept
     * on the message so the next model turn AND the
     * grounding verifier can both see the actual data. The
     * previous design discarded the rows after the tool
     * call finished, which forced the model to answer
     * without the data and was the root cause of the
     * H&M/Myntra fabrication the user reported.
     */
    data class ToolResultMessage(
        override val id: String,
        val sql: String,
        val columns: List<String>,
        val rowCount: Int,
        val truncated: Boolean,
        val error: String?,
        val rows: List<Map<String, kotlinx.serialization.json.JsonElement>> = emptyList(),
    ) : AgenticInsightsMessage

    /**
     * A non-conversational note (e.g. "engine not ready",
     * "cancelled"). Shown at the bottom of the chat list
     * and not forwarded to the model on the next turn.
     */
    data class SystemMessage(
        override val id: String,
        val text: String,
    ) : AgenticInsightsMessage

    /**
     * A "user" turn the orchestrator synthesised after its
     * verifier flagged the previous assistant answer as
     * unsupported by the data. Rendered as a regular
     * bubble so the user can see the verifier is asking
     * for a re-check, and forwarded to the model as a
     * `user` role chat message so the next turn has the
     * verifier prompt in context.
     */
    data class VerifierMessage(
        override val id: String,
        val text: String,
        val attempt: Int,
    ) : AgenticInsightsMessage
}

sealed interface AssistantStatus {
    /** Engine is still streaming; `streamedText` is partial. */
    data object Streaming : AssistantStatus
    /** Stream finished; JSON not yet parsed or parse failed. */
    data object AwaitingParse : AssistantStatus
    /** Parsed into a final [AgenticAction.Answer]. */
    data object Complete : AssistantStatus
    /** Parse failed; UI shows the raw stream as a warning. */
    data class ParseFailed(val reason: String) : AssistantStatus
}

sealed interface ToolCallStatus {
    data object Running : ToolCallStatus
    data object Complete : ToolCallStatus
    data class Failed(val reason: String) : ToolCallStatus
}
