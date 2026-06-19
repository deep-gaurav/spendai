package com.spendai.app.domain.agent.insights

import android.util.Log
import com.spendai.app.domain.agent.AgentPrompt
import com.spendai.app.inference.ChatMessage
import com.spendai.app.inference.GemmaInferenceEngine
import com.spendai.app.inference.InferenceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Multi-turn orchestrator for the agentic insights flow.
 *
 * Owns the conversation list, the system prompt, and the
 * "while the model is talking / while the tool is running"
 * state machine. The ViewModel layer is a thin pass-through;
 * all business logic lives here so the same orchestrator can
 * be exercised from instrumented tests.
 *
 * ## Conversation shape
 *
 * The orchestrator stores the conversation as
 * [AgenticInsightsMessage]s, but feeds the engine a list of
 * [ChatMessage]s. The conversion is straightforward:
 *  - [AgenticInsightsMessage.UserMessage] -> `user` turn.
 *  - [AgenticInsightsMessage.AssistantMessage] (any status) ->
 *    `assistant` turn with the streamed text.
 *  - [AgenticInsightsMessage.ToolResultMessage] -> `tool` turn.
 *  - [AgenticInsightsMessage.MutationToolResultMessage] ->
 *    `tool` turn with a plain-text summary of the mutation.
 *  - System / ToolCall / MutationToolCall messages are NOT
 *    fed to the model; the system prompt and tool calls are
 *    reconstructed from the assistant text + tool result
 *    pair on the next turn.
 *
 * ## Loop invariants
 *
 *  - The conversation list is append-only within a single
 *    user turn. The orchestrator mutates the last assistant
 *    or tool message in place (e.g. the assistant's streamed
 *    text grows as the engine emits chunks).
 *  - A user turn consists of:
 *      user -> (assistant + tool_call + tool_result) *
 *    and ends with an assistant `answer` turn. The
 *    orchestrator enforces [AgenticAction.MAX_TURNS] tool
 *    rounds and synthesises an "I gave up" answer when the
 *    model keeps calling tools past the cap.
 *  - Cancellation cancels both the in-flight engine call and
 *    the orchestrator's own coroutine. The conversation
 *    stays as-is; the user can re-send.
 *
 * ## Concurrency
 *
 * The orchestrator runs its work in an internal
 * [SupervisorJob] scope so a thrown cancellation does not
 * tear down the orchestrator itself. The ViewModel cancels
 * the scope on `clear()` / ViewModel cleared; cancellation
 * elsewhere is per-turn.
 */
class AgenticInsightsAgent(
    private val engine: GemmaInferenceEngine,
    private val sqlExecutor: SqlExecutor,
    private val verifier: AnswerVerifier,
    private val merchantMutator: MerchantMutator? = null,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _messages = MutableStateFlow<List<AgenticInsightsMessage>>(emptyList())
    val messages: StateFlow<List<AgenticInsightsMessage>> = _messages.asStateFlow()

    private val _status = MutableStateFlow<AgenticStatus>(AgenticStatus.Idle)
    val status: StateFlow<AgenticStatus> = _status.asStateFlow()

    private val _currentJob = MutableStateFlow<Job?>(null)

    /**
     * Monotonic counter so the UI can show "turn 2 of 4"
     * without the ViewModel having to derive it.
     */
    private val turnCounter = AtomicLong(0)

    /**
     * In-memory log of every meaningful event the
     * orchestrator produced. Surfaced under the chat screen's
     * debug toggle so the user (and the developer) can audit
     * the full pipeline: system prompt, conversation history,
     * raw model output, parsed action, SQL, raw result.
     */
    private val _debugLog = MutableStateFlow<List<AgenticDebugEntry>>(emptyList())
    val debugLog: StateFlow<List<AgenticDebugEntry>> = _debugLog.asStateFlow()

    /**
     * User-controlled toggle. The chat screen exposes a
     * button to flip this; the screen reads it to decide
     * whether to render the debug panel.
     */
    private val _debugEnabled = MutableStateFlow(false)
    val debugEnabled: StateFlow<Boolean> = _debugEnabled.asStateFlow()

    fun toggleDebug() {
        _debugEnabled.value = !_debugEnabled.value
    }

    /**
     * Verifier toggle. Default OFF — the user said the
     * verifier's re-prompt loop was getting in the way of
     * quick chats, so the orchestrator skips
     * [com.spendai.app.domain.agent.insights.AnswerVerifier]
     * entirely unless this is on. The chat screen exposes
     * a button to flip it back on when the user wants the
     * ground-truth check to run. When off, every answer is
     * accepted on the first try; no Verifier bubbles or
     * "I could not produce a verified answer" replacements
     * are emitted.
     */
    private val _verifierEnabled = MutableStateFlow(false)
    val verifierEnabled: StateFlow<Boolean> = _verifierEnabled.asStateFlow()

    fun setVerifierEnabled(enabled: Boolean) {
        _verifierEnabled.value = enabled
    }

    /**
     * Verifier attempts for the current user turn. Reset
     * on `sendMessage`. Bounded by [MAX_VERIFIER_ATTEMPTS].
     */
    private val verifierAttempts = AtomicLong(0)

    /**
     * Parse-failure retries for the current user turn. Reset
     * on `sendMessage`. Bounded by [MAX_PARSE_RETRIES].
     *
     * Reasoning models (gpt-oss:120b, deepseek-r1, qwen3)
     * occasionally emit a turn that does not contain a parseable
     * action — usually a stray `<think>` block, a model
     * timeout mid-stream, or a string the model's own
     * templating tried to fill in. When that happens the
     * orchestrator used to silently bail. We now nudge the
     * model with a short re-prompt and try again, up to
     * [MAX_PARSE_RETRIES] times.
     */
    private val parseFailureRetries = AtomicLong(0)

    /**
     * The last user question the orchestrator is
     * answering. Captured when [sendMessage] runs and
     * forwarded to the verifier so the judge model sees
     * the same question the agent saw.
     */
    private var currentUserQuestion: String = ""

    /**
     * Tool-result rows keyed by the [ToolResultMessage.id]
     * the orchestrator assigned. The verifier looks up the
     * most recent successful result by walking
     * [messages] and reading the rows off this map. Kept
     * here rather than on the message itself so the public
     * message type stays free of heavy data.
     */
    private val rowsByToolCallId = mutableMapOf<String, List<Map<String, kotlinx.serialization.json.JsonElement>>>()

    /**
     * Send a new user message and start the agent loop. The
     * call is non-blocking - returns immediately, work runs
     * on the orchestrator's scope. If a previous turn is
     * still running, it is cancelled first.
     */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        cancelInFlight()
        val userMsg = AgenticInsightsMessage.UserMessage(
            id = uuid(),
            text = trimmed,
            createdAt = nowMillis(),
        )
        appendMessage(userMsg)
        currentUserQuestion = trimmed
        verifierAttempts.set(0)
        parseFailureRetries.set(0)
        _currentJob.value = scope.launch {
            runTurnLoop(userMsg)
        }
    }

    /**
     * Cancel any in-flight engine call and reset the
     * orchestrator to [AgenticStatus.Idle]. Safe to call
     * from any thread.
     */
    fun cancel() {
        cancelInFlight()
        // Engine may be in Busy; nudge it back to Ready so the
        // next sendMessage() is not gated by a stale Busy.
        scope.launch { runCatching { engine.cancelCurrent() } }
    }

    /**
     * Wipe the conversation. Used by the "clear" button on
     * the chat screen and by ViewModel.onCleared.
     */
    fun clear() {
        cancelInFlight()
        _messages.value = emptyList()
        _status.value = AgenticStatus.Idle
        turnCounter.set(0)
        verifierAttempts.set(0)
        parseFailureRetries.set(0)
        _debugLog.value = emptyList()
        rowsByToolCallId.clear()
        currentUserQuestion = ""
        // Note: we deliberately do NOT reset
        // _verifierEnabled on clear(); the toggle is a
        // user preference, not a per-conversation setting.
    }

    /**
     * Tear the orchestrator down. Cancels in-flight work
     * and stops the scope. Idempotent.
     */
    fun shutdown() {
        cancelInFlight()
        scope.cancel()
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    private fun cancelInFlight() {
        _currentJob.value?.cancel()
        _currentJob.value = null
        if (_status.value is AgenticStatus.Thinking || _status.value is AgenticStatus.RunningTool) {
            _status.value = AgenticStatus.Idle
        }
    }

    private suspend fun runTurnLoop(userMsg: AgenticInsightsMessage.UserMessage) {
        if (engine.state.value !is InferenceState.Ready) {
            appendMessage(
                AgenticInsightsMessage.SystemMessage(
                    id = uuid(),
                    text = "The model is not ready yet. Open the test screen once to initialise it, then come back.",
                )
            )
            return
        }

        val systemPrompt = AgenticInsightsSystemPrompt.build(nowMillis = nowMillis())
        debug(
            kind = AgenticDebugKind.SYSTEM_PROMPT,
            title = "System prompt (${systemPrompt.length} chars)",
            content = systemPrompt,
        )
        var turns = 0

        while (true) {
            if (!scope.isActive) return
            if (turns >= AgenticAction.MAX_TURNS) {
                appendMessage(
                    AgenticInsightsMessage.SystemMessage(
                        id = uuid(),
                        text = "I went past my tool-call budget without finding an answer. " +
                            "Try a narrower question, or break it into two.",
                    )
                )
                return
            }
            turns++

            // Step 1: ask the model what to do.
            val assistantId = uuid()
            val assistantMsg = AgenticInsightsMessage.AssistantMessage(
                id = assistantId,
                streamedText = "",
                parsed = null,
                status = AssistantStatus.Streaming,
            )
            appendMessage(assistantMsg)
            _status.value = AgenticStatus.Thinking

            debug(
                kind = AgenticDebugKind.USER_TURN,
                title = "Conversation length: ${_messages.value.size} messages",
                content = _messages.value.takeLast(2).joinToString(separator = "\n---\n") { msg ->
                    when (msg) {
                        is AgenticInsightsMessage.UserMessage -> "[user] ${msg.text}"
                        is AgenticInsightsMessage.VerifierMessage -> "[verifier] ${msg.text}"
                        is AgenticInsightsMessage.AssistantMessage -> "[assistant] ${msg.streamedText.take(200)}"
                        else -> "[${msg::class.simpleName}]"
                    }
                },
            )
            val history = buildChatHistory(systemPrompt, messages = _messages.value)
            debug(
                kind = AgenticDebugKind.MODEL_REQUEST,
                title = "Sent to model: ${history.size} messages",
                content = history.joinToString(separator = "\n---\n") { msg ->
                    "[${msg.role}] ${msg.content.take(400)}"
                },
            )
            val rawText = try {
                streamAssistantTurn(assistantId, history)
            } catch (ce: CancellationException) {
                rewriteAssistant(assistantId, streamedText = "(cancelled)", status = AssistantStatus.AwaitingParse)
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "Model call failed", t)
                rewriteAssistant(
                    assistantId,
                    streamedText = "",
                    status = AssistantStatus.ParseFailed("Model call failed: ${t.message ?: t.javaClass.simpleName}"),
                )
                return
            }

            // Step 2: parse the model's response. The
            // reasoning model path tries every balanced
            // JSON object and prefers the LAST one whose
            // `action` is `answer` (so a model that
            // pre-planned a tool call and an answer in one
            // turn is honoured on its final intent).
            val parsed = parseAction(rawText)
            if (parsed == null) {
                val attempt = parseFailureRetries.incrementAndGet().toInt()
                if (attempt > MAX_PARSE_RETRIES) {
                    // Three strikes — surface the raw stream
                    // so the user can see what came back, then
                    // give up. A future sendMessage() starts
                    // fresh.
                    rewriteAssistant(
                        assistantId,
                        streamedText = rawText,
                        status = AssistantStatus.ParseFailed(
                            "Could not extract a parseable action after " +
                                "$MAX_PARSE_RETRIES retries. The model reply " +
                                "had no valid JSON object.",
                        ),
                    )
                    appendMessage(
                        AgenticInsightsMessage.SystemMessage(
                            id = uuid(),
                            text = "I could not parse a response from the model " +
                                "after $MAX_PARSE_RETRIES retries. Try sending " +
                                "your question again, or simplify it.",
                        )
                    )
                    _status.value = AgenticStatus.Idle
                    return
                }
                // Soft retry: keep the streamed text in the
                // chat so the user can see what came back,
                // mark the assistant as ParseFailed for this
                // turn, and inject a short user-side nudge
                // asking the model to re-emit a clean action.
                rewriteAssistant(
                    assistantId,
                    streamedText = rawText,
                    status = AssistantStatus.ParseFailed(
                        "Could not extract a JSON action (retry $attempt/$MAX_PARSE_RETRIES). " +
                            "Asking the model to try again.",
                    ),
                )
                appendMessage(
                    AgenticInsightsMessage.InternalNudge(
                        id = uuid(),
                        text = "Your previous reply did not contain a parseable action. " +
                            "Reply with a single JSON object on its own line, with no " +
                            "template placeholders ({{...}}), no `<think>` block, " +
                            "and no prose after the JSON.",
                    )
                )
                // Continue the loop — the next iteration will
                // see the nudge as the most recent user turn
                // and stream a fresh assistant reply.
                continue
            }
            // Parsed successfully; reset the retry counter so a
            // later mid-turn failure gets a fresh budget.
            parseFailureRetries.set(0)
            rewriteAssistant(
                assistantId,
                streamedText = rawText,
                parsed = parsed,
                status = AssistantStatus.Complete,
            )
            debug(
                kind = AgenticDebugKind.PARSED_ACTION,
                title = when (parsed) {
                    is AgenticAction.Answer -> "Parsed: answer"
                    is AgenticAction.QueryDatabase -> "Parsed: query_database"
                    is AgenticAction.MutateMerchant -> "Parsed: mutate_merchant"
                },
                content = rawText,
            )

            // Step 3: dispatch the action.
            when (parsed) {
                is AgenticAction.Answer -> {
                    val verdict = gradeAnswer(parsed, _messages.value)
                    if (!verdict.isFabrication) {
                        _status.value = AgenticStatus.Idle
                        return
                    }
                    val attempt = verifierAttempts.incrementAndGet().toInt()
                    if (attempt > MAX_VERIFIER_ATTEMPTS) {
                        // Replace the unverified assistant
                        // message in place so the user never
                        // sees the fabricated text.
                        val replacement =
                            "I could not produce a verified answer. " +
                                "An independent check found specific claims in my " +
                                "last reply that were not in the database rows " +
                                "(" + verdict.unverifiedClaims.take(5).joinToString(", ") + "). " +
                                "Try rephrasing the question or widening the date range."
                        rewriteAssistant(
                            assistantId,
                            streamedText = replacement,
                            status = AssistantStatus.Complete,
                        )
                        // Also clear any charts the model
                        // emitted alongside the unverified
                        // answer so the UI does not render
                        // a graph that cited fake numbers.
                        rewriteAssistantClearParsed(assistantId)
                        debug(
                            kind = AgenticDebugKind.VERIFIER_GAVE_UP,
                            title = "Verifier gave up after $MAX_VERIFIER_ATTEMPTS attempts; replaced answer",
                            content = "Unverified claims: " +
                                verdict.unverifiedClaims.joinToString(" | ") +
                                "\n\nEvidence: " + verdict.evidence,
                        )
                        _status.value = AgenticStatus.Idle
                        return
                    }
                    debug(
                        kind = AgenticDebugKind.VERIFIER_TRIGGERED,
                        title = "Verifier triggered (attempt $attempt/$MAX_VERIFIER_ATTEMPTS)",
                        content = "Unverified claims: " + verdict.unverifiedClaims.joinToString(" | ") +
                            "\n\nEvidence: " + verdict.evidence,
                    )
                    appendMessage(
                        AgenticInsightsMessage.VerifierMessage(
                            id = uuid(),
                            text = "Verifier (attempt $attempt/$MAX_VERIFIER_ATTEMPTS): " +
                                "An independent check of your last answer found specific claims " +
                                "that are NOT in the database rows I gave you: " +
                                verdict.unverifiedClaims.joinToString(", ") +
                                ". The judge said: \"" + verdict.evidence + "\". " +
                                "Re-check your reasoning. If your query was too narrow, widen the " +
                                "range or drop a filter. If the database truly has no matching data, " +
                                "answer plainly: \"I have no matching transactions.\" " +
                                "Do not invent merchants, amounts, dates, or rankings.",
                            attempt = attempt,
                        )
                    )
                    // Loop continues - the verifier message
                    // becomes a user turn on the next
                    // iteration.
                }
                is AgenticAction.QueryDatabase -> {
                    val toolCallId = uuid()
                    appendMessage(
                        AgenticInsightsMessage.ToolCallMessage(
                            id = toolCallId,
                            sql = parsed.sql,
                            thought = parsed.thought,
                            status = ToolCallStatus.Running,
                        )
                    )
                    debug(
                        kind = AgenticDebugKind.TOOL_CALL,
                        title = "SQL (thought: ${parsed.thought.take(80)})",
                        content = parsed.sql,
                    )
                    _status.value = AgenticStatus.RunningTool

                    val (result, error) = sqlExecutor.run(parsed.sql)
                    debug(
                        kind = AgenticDebugKind.TOOL_RESULT,
                        title = if (error != null) "Error: $error"
                                else "Result: ${result.rowCount} row(s)" +
                                     if (result.truncated) " (truncated)" else "",
                        content = if (error != null) error
                                  else SqlExecutor.resultAsJson(result),
                    )
                    val resultId = uuid()
                    rowsByToolCallId[resultId] = result.rows
                    if (error != null) {
                        rewriteToolCall(toolCallId, ToolCallStatus.Failed(error))
                        appendMessage(
                            AgenticInsightsMessage.ToolResultMessage(
                                id = resultId,
                                sql = parsed.sql,
                                columns = result.columns,
                                rowCount = result.rowCount,
                                truncated = result.truncated,
                                error = error,
                            )
                        )
                    } else {
                        rewriteToolCall(toolCallId, ToolCallStatus.Complete)
                        appendMessage(
                            AgenticInsightsMessage.ToolResultMessage(
                                id = resultId,
                                sql = parsed.sql,
                                columns = result.columns,
                                rowCount = result.rowCount,
                                truncated = result.truncated,
                                error = null,
                                rows = result.rows,
                            )
                        )
                    }
                    // Loop back: feed the rows to the model on the next iteration.
                }
                is AgenticAction.MutateMerchant -> {
                    val toolCallId = uuid()
                    appendMessage(
                        AgenticInsightsMessage.MutationToolCallMessage(
                            id = toolCallId,
                            action = parsed,
                            status = ToolCallStatus.Running,
                        )
                    )
                    debug(
                        kind = AgenticDebugKind.TOOL_CALL,
                        title = "mutate_merchant (thought: ${parsed.thought.take(80)})",
                        content = "matchByName=${parsed.matchByName} matchById=${parsed.matchById} " +
                            "setIsSelf=${parsed.setIsSelf} clearIsSelf=${parsed.clearIsSelf} " +
                            "addMetadata=${parsed.addMetadata} removeMetadata=${parsed.removeMetadata}",
                    )
                    _status.value = AgenticStatus.RunningTool

                    val result = runMutation(parsed)
                    debug(
                        kind = AgenticDebugKind.TOOL_RESULT,
                        title = if (result.error != null) "Error: ${result.error}"
                                else "Mutation: isSelf=${result.isSelfNewValue} " +
                                    "added=${result.metadataAdded.size} " +
                                    "removed=${result.metadataRemoved.size} " +
                                    "affected=${result.affectedTransactionIds.size} " +
                                    "links=${result.selfTransferLinksWritten} " +
                                    "reprompts=${result.repromptsEnqueued}",
                        content = if (result.error != null) result.error
                                  else AgentPrompt.JSON.encodeToString(
                                      MerchantMutator.MutationResult.serializer(),
                                      result,
                                  ),
                    )
                    if (result.error != null) {
                        rewriteMutationToolCall(toolCallId, ToolCallStatus.Failed(result.error))
                    } else {
                        rewriteMutationToolCall(toolCallId, ToolCallStatus.Complete)
                    }
                    appendMessage(
                        AgenticInsightsMessage.MutationToolResultMessage(
                            id = uuid(),
                            result = result,
                        )
                    )
                    // Loop back: the next turn sees the
                    // mutation result and either calls
                    // another tool or answers in plain
                    // English.
                }
            }
        }
    }

    /**
     * Run the [MerchantMutator] if it's wired, otherwise
     * synthesise an "unavailable" result. The orchestrator
     * stays unit-testable without a mutator instance (e.g.
     * in tests that only exercise the SQL tool path).
     */
    private suspend fun runMutation(
        action: AgenticAction.MutateMerchant,
    ): MerchantMutator.MutationResult {
        val mutator = merchantMutator
            ?: return MerchantMutator.MutationResult(
                matchedMerchantId = null,
                matchedMerchantName = null,
                isSelfChanged = false,
                isSelfNewValue = false,
                error = "The merchant mutation tool is not available in this build.",
            )
        return mutator.mutate(action)
    }

    private suspend fun streamAssistantTurn(
        assistantId: String,
        history: List<ChatMessage>,
    ): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        engine.generateChatTracking(
            messages = history,
            stepLabel = "agentic.think",
        ).collect { chunk ->
            sb.append(chunk)
            rewriteAssistant(
                assistantId,
                streamedText = sb.toString(),
                status = AssistantStatus.Streaming,
            )
        }
        rewriteAssistant(
            assistantId,
            streamedText = sb.toString(),
            status = AssistantStatus.AwaitingParse,
        )
        sb.toString()
    }

    private fun parseAction(raw: String): AgenticAction? {
        // Strip <think>...</think> blocks (gpt-oss:120b,
        // deepseek-r1, qwen3) before scanning for JSON, then
        // try every balanced object. When more than one parses
        // we prefer the LAST one whose `action` is `answer`,
        // so a reasoning model that pre-planned a tool call
        // and an answer in the same turn is honoured on its
        // final intent.
        return com.spendai.app.domain.agent.AgentJsonParse
            .tryParsePreferringAnswer(
                raw = raw,
                answerAction = "answer",
                serializer = AgenticAction.serializer(),
            )
    }

    private fun buildChatHistory(
        systemPrompt: String,
        messages: List<AgenticInsightsMessage>,
    ): List<ChatMessage> {
        val out = ArrayList<ChatMessage>(messages.size + 1)
        out.add(ChatMessage(ChatMessage.ROLE_SYSTEM, systemPrompt))
        var lastAssistantText: String? = null
        for (msg in messages) {
            when (msg) {
                is AgenticInsightsMessage.UserMessage -> {
                    out.add(ChatMessage(ChatMessage.ROLE_USER, msg.text))
                }
                is AgenticInsightsMessage.VerifierMessage -> {
                    // Verifier prompts are forwarded to the
                    // model as `user` turns. The "Verifier:"
                    // prefix tells the model the prompt is from
                    // the orchestrator, not the actual user.
                    out.add(ChatMessage(ChatMessage.ROLE_USER, msg.text))
                }
                is AgenticInsightsMessage.InternalNudge -> {
                    // Parse-failure nudges are forwarded to the
                    // model as `user` turns. The "Parser retry:"
                    // prefix is the model's cue that this is an
                    // orchestrator-synthesised re-prompt, not a
                    // real user question. Without this forwarding
                    // the retry loop is a no-op: the model never
                    // sees the nudge and keeps producing the same
                    // unparseable response.
                    out.add(
                        ChatMessage(
                            ChatMessage.ROLE_USER,
                            "Parser retry: " + msg.text,
                        ),
                    )
                }
                is AgenticInsightsMessage.AssistantMessage -> {
                    if (msg.streamedText.isNotEmpty() && msg.streamedText != lastAssistantText) {
                        out.add(ChatMessage(ChatMessage.ROLE_ASSISTANT, msg.streamedText))
                        lastAssistantText = msg.streamedText
                    }
                }
                is AgenticInsightsMessage.ToolResultMessage -> {
                    if (msg.error != null) {
                        out.add(
                            ChatMessage(
                                role = ChatMessage.ROLE_TOOL,
                                content = "Error: ${msg.error}",
                                name = "query_database",
                            )
                        )
                    } else {
                        // Send the actual rows to the model.
                        // The previous design threw the rows
                        // away after the tool call finished
                        // and only sent back rowCount +
                        // columns. The model could not
                        // ground its next answer in data it
                        // never saw, which produced the
                        // "₹4,100 / Myntra" fabrication
                        // bug. With rows in the tool result
                        // the model has the data it needs to
                        // answer accurately; the verifier
                        // then makes sure it did.
                        out.add(
                            ChatMessage(
                                role = ChatMessage.ROLE_TOOL,
                                content = SqlExecutor.resultAsJson(
                                    SqlExecutor.QueryResult(
                                        columns = msg.columns,
                                        rows = rowsByToolCallId[msg.id] ?: msg.rows,
                                        rowCount = msg.rowCount,
                                        truncated = msg.truncated,
                                    )
                                ),
                                name = "query_database",
                            )
                        )
                    }
                }
                is AgenticInsightsMessage.MutationToolResultMessage -> {
                    // The mutation result is sent back as a
                    // short plain-text summary so the model
                    // can confirm in plain English. We do
                    // NOT send the full JSON because the
                    // model already knows what it asked for
                    // and a long payload just consumes
                    // context budget.
                    val r = msg.result
                    val summary = buildString {
                        if (r.error != null) {
                            append("Error: ").append(r.error)
                        } else {
                            append("Matched merchant: ")
                            append(r.matchedMerchantName ?: "<none>")
                            append(" (id=").append(r.matchedMerchantId ?: -1L).append("). ")
                            if (r.isSelfChanged) {
                                append("isSelf is now ").append(r.isSelfNewValue).append(". ")
                            }
                            if (r.metadataAdded.isNotEmpty()) {
                                append("Added metadata: ")
                                r.metadataAdded.forEachIndexed { i, op ->
                                    if (i > 0) append("; ")
                                    append(op.kind).append(" = ").append(op.value)
                                }
                                append(". ")
                            }
                            if (r.metadataRemoved.isNotEmpty()) {
                                append("Removed metadata kinds: ")
                                append(r.metadataRemoved.joinToString(", "))
                                append(". ")
                            }
                            if (r.affectedTransactionIds.isNotEmpty()) {
                                append("Affected ")
                                    .append(r.affectedTransactionIds.size)
                                    .append(" transaction(s). ")
                                if (r.selfTransferLinksWritten > 0) {
                                    append("Wrote ")
                                        .append(r.selfTransferLinksWritten)
                                        .append(" self-transfer link(s). ")
                                }
                                if (r.repromptsEnqueued > 0) {
                                    append("Enqueued ")
                                        .append(r.repromptsEnqueued)
                                        .append(" reprompt job(s). ")
                                }
                            }
                        }
                    }
                    out.add(
                        ChatMessage(
                            role = ChatMessage.ROLE_TOOL,
                            content = summary,
                            name = "mutate_merchant",
                        )
                    )
                }
                is AgenticInsightsMessage.ToolCallMessage,
                is AgenticInsightsMessage.MutationToolCallMessage,
                is AgenticInsightsMessage.SystemMessage,
                -> {
                    // Not a model turn; reconstructed from the surrounding
                    // assistant + tool_result pair, no need to forward.
                }
            }
        }
        return out
    }

    private fun appendMessage(msg: AgenticInsightsMessage) {
        _messages.value = _messages.value + msg
    }

    /**
     * Append a [AgenticDebugEntry] to the debug log. Cheap;
     * called at every meaningful boundary. The log is a
     * snapshot list (immutable update) so the UI sees a
     * stable render between emissions.
     */
    private fun debug(
        kind: AgenticDebugKind,
        title: String,
        content: String,
    ) {
        _debugLog.value = _debugLog.value + AgenticDebugEntry(
            id = uuid(),
            timestamp = nowMillis(),
            kind = kind,
            title = title,
            content = content,
        )
    }

    private fun rewriteAssistant(
        id: String,
        streamedText: String,
        status: AssistantStatus,
        parsed: AgenticAction? = null,
    ) {
        _messages.value = _messages.value.map { m ->
            if (m is AgenticInsightsMessage.AssistantMessage && m.id == id) {
                m.copy(streamedText = streamedText, status = status, parsed = parsed ?: m.parsed)
            } else m
        }
    }

    private fun rewriteToolCall(id: String, status: ToolCallStatus) {
        _messages.value = _messages.value.map { m ->
            if (m is AgenticInsightsMessage.ToolCallMessage && m.id == id) {
                m.copy(status = status)
            } else m
        }
    }

    private fun rewriteMutationToolCall(id: String, status: ToolCallStatus) {
        _messages.value = _messages.value.map { m ->
            if (m is AgenticInsightsMessage.MutationToolCallMessage && m.id == id) {
                m.copy(status = status)
            } else m
        }
    }

    /**
     * Strip the parsed action off an assistant message
     * (and any chart payload it carried). Used after the
     * verifier gives up so the UI does not render a chart
     * that was grounded in fabricated numbers.
     */
    private fun rewriteAssistantClearParsed(id: String) {
        _messages.value = _messages.value.map { m ->
            if (m is AgenticInsightsMessage.AssistantMessage && m.id == id) {
                m.copy(parsed = null)
            } else m
        }
    }

    /**
     * Call the [AnswerVerifier] with the most recent
     * successful tool result and the user's original
     * question. Returns a [VerifierVerdict] (always - the
     * verifier falls back to [VerifierVerdict.FAIL_SAFE]
     * on engine error, JSON parse error, or missing
     * fields). When there is no successful tool result
     * to grade against the answer is taken as a pass; this
     * covers cases like "I cannot help with that" where
     * the agent never ran the SQL tool.
     */
    private suspend fun gradeAnswer(
        answer: AgenticAction.Answer,
        messages: List<AgenticInsightsMessage>,
    ): VerifierVerdict {
        // Verifier is opt-in. The user said the re-prompt
        // loop was getting in the way of quick chats, so
        // when [verifierEnabled] is off (the default) we
        // short-circuit to the grounded verdict. The
        // AnswerVerifier instance is still constructed for
        // the moments the user flips the toggle on, but the
        // model call never fires.
        if (!_verifierEnabled.value) {
            return VerifierVerdict(verdict = "grounded")
        }
        val lastResult = messages.lastOrNull { it is AgenticInsightsMessage.ToolResultMessage }
            as? AgenticInsightsMessage.ToolResultMessage
        if (lastResult == null || lastResult.error != null) {
            // The agent answered without running a tool
            // (e.g. a refusal). Nothing to grade against.
            return VerifierVerdict(verdict = "grounded")
        }
        val rows = rowsByToolCallId[lastResult.id] ?: lastResult.rows
        return verifier.verify(
            userQuestion = currentUserQuestion,
            sql = lastResult.sql,
            rows = rows,
            agentAnswer = answer.text,
        )
    }

    private fun uuid(): String = UUID.randomUUID().toString()

    companion object {
        private const val TAG = "AgenticInsightsAgent"

        /**
         * Maximum number of times the verifier will re-prompt
         * the model when an answer contains specific facts not
         * supported by the most recent tool result. After
         * this many attempts the orchestrator replaces the
         * assistant's unverified answer in place with a
         * "no reliable answer" message.
         */
        const val MAX_VERIFIER_ATTEMPTS: Int = 3

        /**
         * Maximum number of retries when the model emits a
         * turn that does not contain a parseable action.
         * Reasoning models occasionally drift (stray think
         * block, mid-stream timeout, model templating
         * placeholders). On each retry the orchestrator
         * appends a SystemMessage asking the model to
         * re-emit a clean JSON object. After this many
         * retries the orchestrator surfaces the raw stream
         * and gives up the turn.
         */
        const val MAX_PARSE_RETRIES: Int = 2
    }
}

/**
 * Coarse status of the orchestrator. The UI uses this to
 * drive the "thinking" / "running tool" indicator at the
 * bottom of the chat list.
 */
sealed interface AgenticStatus {
    data object Idle : AgenticStatus
    data object Thinking : AgenticStatus
    data object RunningTool : AgenticStatus
}
