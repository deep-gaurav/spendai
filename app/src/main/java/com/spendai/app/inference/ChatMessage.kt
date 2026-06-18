package com.spendai.app.inference

/**
 * One entry in a multi-turn chat conversation.
 *
 * Used by [GemmaInferenceEngine.generateChatTracking] to feed a
 * full conversation into a chat-completions endpoint. The set of
 * valid roles depends on the provider:
 *
 *  - Gemini: "user" and "model" are the only conversation roles
 *    (system is a separate top-level field).
 *  - Claude: "user" and "assistant" are valid; "system" is also a
 *    top-level field separate from the messages array.
 *  - OpenAI / Ollama / Kimi / Zhipu: "system", "user", "assistant"
 *    are all valid in the messages array.
 *
 * The [GemmaInferenceEngine] layer translates the generic
 * [ChatMessage] into the right shape for the configured provider,
 * so callers can think in terms of a single, unified role set.
 *
 * The [tool] role is used by the agentic insights flow to feed
 * tool outputs back to the model. Providers handle this
 * differently (OpenAI has a real "tool" role, Gemini has
 * "functionResponse" parts, Claude does not have it natively and
 * is approximated via a "user" turn with a structured prefix).
 *
 * @property role one of "system" | "user" | "assistant" | "tool".
 * @property content the textual body of the message. For tool
 *   responses, the convention is a JSON string the model can
 *   re-parse.
 * @property name optional tool name. Set when [role] is "tool"
 *   and the provider needs to attribute the response to a
 *   specific function call.
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val name: String? = null,
) {
    companion object {
        const val ROLE_SYSTEM: String = "system"
        const val ROLE_USER: String = "user"
        const val ROLE_ASSISTANT: String = "assistant"
        const val ROLE_TOOL: String = "tool"
    }
}
