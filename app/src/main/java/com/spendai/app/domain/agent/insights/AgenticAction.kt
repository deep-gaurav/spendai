package com.spendai.app.domain.agent.insights

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The contract the on-device model emits at the end of every
 * agentic turn.
 *
 * The model is free to think aloud in plain text before the
 * closing JSON object; [com.spendai.app.domain.agent.AgentJsonParse]
 * extracts the first balanced `{ ... }` block from the response
 * and this class is what the orchestrator parses it into.
 *
 * Two actions are supported:
 *
 *  - [AgenticAction.QueryDatabase] calls the SQL tool and feeds
 *    the result back to the model in the next turn.
 *  - [AgenticAction.Answer] finalises the conversation with
 *    prose + optional charts for the UI to render.
 *
 * The orchestrator enforces a hard turn cap ([MAX_TURNS]) so a
 * runaway model cannot loop forever. If the cap is hit while the
 * model is still calling tools, the orchestrator synthesises a
 * [AgenticAction.Answer] with whatever context it has.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("action")
@Serializable
sealed class AgenticAction {

    /**
     * Free-form reasoning the model produced before deciding
     * what to do. Surfaced to the UI as the "thinking" text in
     * the assistant bubble and never re-fed to the model as a
     * standalone turn.
     */
    abstract val thought: String

    /**
     * Ask the SQL tool to execute a read-only SELECT and feed
     * the rows back. The model's next turn will see the result
     * and either call another tool or answer.
     */
    @Serializable
    @SerialName("query_database")
    data class QueryDatabase(
        override val thought: String,
        val sql: String,
    ) : AgenticAction()

    /**
     * Final answer. `text` is shown verbatim; `charts` is
     * rendered by the UI as inline sticker cards under the
     * text. An empty `charts` list is valid for prose-only
     * answers.
     */
    @Serializable
    @SerialName("answer")
    data class Answer(
        override val thought: String,
        val text: String,
        val charts: List<AgenticChart> = emptyList(),
    ) : AgenticAction()

    companion object {
        /**
         * Hard cap on consecutive tool-calling turns before
         * the orchestrator synthesises a final answer. The
         * Gemini Gemma 4 31B IT model rarely needs more than
         * two or three tool calls to answer an analytics
         * question, so this leaves headroom for genuine
         * multi-step questions without letting a misbehaving
         * model loop.
         */
        const val MAX_TURNS: Int = 6
    }
}
