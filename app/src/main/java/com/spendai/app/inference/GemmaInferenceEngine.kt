package com.spendai.app.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Session
import com.google.ai.edge.litertlm.SessionConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.io.File

/**
 * UI progress object
 */
data class InferenceStepProgress(
    val stepLabel: String,
    val tokensEmitted: Int,
    val startedAt: Long = System.currentTimeMillis(),
) {
    fun toLabel(): String {
        val elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
        val mins = elapsedSec / 60
        val secs = elapsedSec % 60
        val elapsed = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
        val step = if (stepLabel.isEmpty()) "" else " (${stepLabel})"
        return "Decoded $tokensEmitted tokens$step · $elapsed"
    }
}

/**
 * Public state of the engine.
 */
sealed interface InferenceState {
    data object Uninitialized : InferenceState
    data object Loading : InferenceState
    data class Ready(val backendLabel: String) : InferenceState
    data class Busy(val progress: InferenceStepProgress) : InferenceState
    data class Error(val message: String, val cause: Throwable? = null) : InferenceState
}

/**
 * Inference Engine that delegates to either the Gemini API (production) or the
 * local LiteRT-LM runtime (fallback or JVM unit tests).
 */
class GemmaInferenceEngine {

    private val _state = MutableStateFlow<InferenceState>(InferenceState.Uninitialized)
    val state: StateFlow<InferenceState> = _state.asStateFlow()

    private val mutex = Mutex()

    // Test-only / local fallback variables
    private var engine: Engine? = null
    private var currentConfig: InferenceConfig? = null
    private var currentBackendLabel: String = ""
    private var initContext: Context? = null
    private var currentStrategy: BackendStrategy = BackendStrategy.CpuOnly
    private var sessionConfig: SessionConfig? = null
    private var currentSession: Session? = null
    private var consecutiveFailures: Int = 0

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    suspend fun initialize(
        context: Context,
        config: InferenceConfig = InferenceConfig(),
        strategy: BackendStrategy = strategyFor(config),
    ) = withContext(Dispatchers.IO) {
        if (_state.value is InferenceState.Ready) {
            Log.d(TAG, "Engine already READY; skipping re-initialization")
            return@withContext
        }
        if (_state.value is InferenceState.Loading) {
            Log.d(TAG, "Engine already LOADING; skipping re-initialization")
            return@withContext
        }

        _state.value = InferenceState.Loading
        currentConfig = config
        initContext = context.applicationContext
        currentStrategy = strategy
        sessionConfig = buildSessionConfig(config)
        consecutiveFailures = 0

        // In production, we do NOT load the 2.58 GB local model, and instead
        // initialize directly to the Gemini API ready state.
        currentBackendLabel = "Gemini API"
        _state.value = InferenceState.Ready(currentBackendLabel)
        Log.i(TAG, "Engine READY on $currentBackendLabel")
    }

    suspend fun generatePrediction(prompt: String): String = withContext(Dispatchers.IO) {
        require(_state.value is InferenceState.Ready) {
            "Engine not READY (state=${_state.value}). Call initialize() first."
        }
        val sb = StringBuilder()
        runSession(prompt = prompt, stepLabel = "", maxOutputTokens = null) { chunk -> sb.append(chunk) }
        sb.toString()
    }

    /**
     * Streaming variant. A1 / A2 both use this so the home card can
     * show per-token progress. A2 passes [maxOutputTokens] to cap
     * the decode budget for its small JSON output.
     */
    fun generatePredictionTracking(
        prompt: String,
        stepLabel: String,
        maxOutputTokens: Int? = null,
    ): Flow<String> = flow {
        require(_state.value is InferenceState.Ready) {
            "Engine not READY (state=${_state.value}). Call initialize() first."
        }
        runSession(prompt = prompt, stepLabel = stepLabel, maxOutputTokens = maxOutputTokens) { chunk -> emit(chunk) }
    }.flowOn(Dispatchers.IO)

    /**
     * Multi-turn chat variant. Hands a full [ChatMessage] history
     * (system / user / assistant / tool) to the configured
     * provider's chat-completions endpoint and streams the
     * response back. The per-provider request shape is built here
     * so callers stay provider-agnostic.
     *
     * System messages are pulled out of [messages] and routed to
     * the provider's system field where supported (Claude, Gemini,
     * OpenAI). If [systemInstructionOverride] is non-null it
     * replaces any system message in [messages]; this lets the
     * agentic flow own the system prompt and ignore any
     * conversational system turns the model might have produced.
     *
     * Tool messages (role = "tool") are translated per-provider:
     *  - OpenAI / Ollama / Kimi / Zhipu: real "tool" role, with
     *    [ChatMessage.name] as the function name.
     *  - Gemini: a "functionResponse" part inside a "user" turn.
     *  - Claude: a "user" turn with a structured "Tool result:"
     *    prefix (the closest faithful approximation Claude
     *    accepts without breaking the alternation rule).
     *
     * Backoff, retry, and thought-part filtering are inherited
     * from [callExternalApi] via [runSession] - only the
     * request-building path differs.
     */
    fun generateChatTracking(
        messages: List<ChatMessage>,
        stepLabel: String,
        maxOutputTokens: Int? = null,
        systemInstructionOverride: String? = null,
    ): Flow<String> = flow {
        require(_state.value is InferenceState.Ready) {
            "Engine not READY (state=${_state.value}). Call initialize() first."
        }
        runChatSession(
            messages = messages,
            stepLabel = stepLabel,
            maxOutputTokens = maxOutputTokens,
            systemInstructionOverride = systemInstructionOverride,
        ) { chunk -> emit(chunk) }
    }.flowOn(Dispatchers.IO)

    suspend fun cancelCurrent() = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { currentSession?.cancelProcess() }
                .onFailure { Log.w(TAG, "cancelProcess() failed", it) }
        }
        if (_state.value is InferenceState.Busy) {
            _state.value = InferenceState.Ready(currentBackendLabel)
        }
    }

    private suspend fun runSession(
        prompt: String,
        stepLabel: String,
        maxOutputTokens: Int?,
        onChunk: suspend (String) -> Unit,
    ) {
        val eng = engine
        if (eng != null) {
            // Local fallback (tests) — output-token cap is enforced by
            // the local SamplerConfig in SessionConfig; the per-call
            // override is intentionally a no-op here so the unit tests
            // don't need to know about it.
            runSessionLocal(prompt, stepLabel, onChunk, eng)
            return
        }

        // Production: Gemini API
        mutex.withLock {
            val startedAt = System.currentTimeMillis()
            _state.value = InferenceState.Busy(InferenceStepProgress(stepLabel, 0, startedAt))
            try {
                val response = callExternalApi(prompt, maxOutputTokensOverride = maxOutputTokens)
                val tokensCount = response.split(Regex("\\s+")).size
                _state.value = InferenceState.Busy(InferenceStepProgress(stepLabel, tokensCount, startedAt))
                onChunk(response)
                consecutiveFailures = 0
            } catch (t: Throwable) {
                Log.w(TAG, "Inference failed in step $stepLabel: ${t.message}", t)
                consecutiveFailures = 1
                throw t
            } finally {
                if (_state.value is InferenceState.Busy) {
                    _state.value = InferenceState.Ready(currentBackendLabel)
                }
            }
        }
    }

    private suspend fun runSessionLocal(
        prompt: String,
        stepLabel: String,
        onChunk: suspend (String) -> Unit,
        eng: Engine
    ) {
        mutex.withLock {
            val startedAt = System.currentTimeMillis()
            val tokens = java.util.concurrent.atomic.AtomicInteger(0)
            val cfg = sessionConfig ?: error("SessionConfig was null after READY state")
            _state.value = InferenceState.Busy(InferenceStepProgress(stepLabel, 0, startedAt))

            val session: Session = try {
                eng.createSession(cfg)
            } catch (t: Throwable) {
                Log.w(TAG, "createSession() failed: ${t.message}", t)
                if (_state.value is InferenceState.Busy) {
                    _state.value = InferenceState.Ready(currentBackendLabel)
                }
                throw t
            }
            currentSession = session

            try {
                val ch = Channel<String>(Channel.UNLIMITED)
                val cb = object : ResponseCallback {
                    override fun onNext(response: String) {
                        tokens.incrementAndGet()
                        val current = _state.value
                        if (current is InferenceState.Busy) {
                            _state.value = current.copy(
                                progress = current.progress.copy(tokensEmitted = tokens.get()),
                            )
                        }
                        if (response.isNotEmpty()) ch.trySend(response)
                    }

                    override fun onDone() {
                        ch.close()
                    }

                    override fun onError(throwable: Throwable) {
                        Log.w(TAG, "Session.generateContentStream onError: ${throwable.message}")
                        ch.close(throwable)
                    }
                }
                session.generateContentStream(listOf(InputData.Text(prompt)), cb)
                try {
                    for (chunk in ch) onChunk(chunk)
                    consecutiveFailures = 0
                } catch (ce: CancellationException) {
                    throw ce
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    Log.i(TAG, "Inference cancelled (stepLabel=$stepLabel)")
                    if (_state.value is InferenceState.Busy) {
                        _state.value = InferenceState.Ready(currentBackendLabel)
                    }
                    throw t
                }
                Log.w(TAG, "Inference failed in step $stepLabel: ${t.message}", t)
                if (consecutiveFailures < 1) {
                    consecutiveFailures = 1
                    Log.d(TAG, "Per-call failure; next call will get a fresh Session")
                    if (_state.value is InferenceState.Busy) {
                        _state.value = InferenceState.Ready(currentBackendLabel)
                    }
                } else {
                    Log.w(TAG, "Second consecutive failure; reinitialising engine")
                    val reinitOk = runCatching { reinitializeInternal() }.getOrDefault(false)
                    if (reinitOk) {
                        consecutiveFailures = 0
                        if (_state.value is InferenceState.Busy) {
                            _state.value = InferenceState.Ready(currentBackendLabel)
                        }
                    } else {
                        Log.e(TAG, "Engine could not recover; state is now Error")
                        consecutiveFailures = 0
                        _state.value = InferenceState.Error(t.message ?: t.javaClass.simpleName, t)
                    }
                }
                throw t
            } finally {
                currentSession = null
                runCatching { session.close() }
                    .onFailure { Log.w(TAG, "session.close() failed", it) }
                if (_state.value is InferenceState.Busy) {
                    _state.value = InferenceState.Ready(currentBackendLabel)
                }
            }
        }
    }

    /**
     * Multi-turn counterpart of [runSession]. Acquires the
     * engine mutex, dispatches via [callExternalApiChat] (which
     * owns the per-provider request shape and the HTTP retry /
     * backoff), and streams the response back through [onChunk].
     *
     * Cancellation, busy-state bookkeeping, and consecutive
     * failure tracking mirror [runSession] exactly so the
     * engine's state machine does not diverge between the
     * single-turn and multi-turn code paths.
     */
    private suspend fun runChatSession(
        messages: List<ChatMessage>,
        stepLabel: String,
        maxOutputTokens: Int?,
        systemInstructionOverride: String?,
        onChunk: suspend (String) -> Unit,
    ) {
        val eng = engine
        if (eng != null) {
            // Local fallback (tests). The local LiteRT-LM runtime
            // does not currently expose a multi-turn chat API in
            // the version we depend on, so fall back to a single
            // concatenated prompt. The unit tests don't exercise
            // multi-turn, so this path is only a safety net.
            val flattened = messages.joinToString(separator = "\n\n") { m ->
                "[${m.role.uppercase()}]\n${m.content}"
            }
            runSessionLocal(
                prompt = flattened,
                stepLabel = stepLabel,
                onChunk = onChunk,
                eng = eng,
            )
            return
        }

        // Production: chat-completions endpoint
        mutex.withLock {
            val startedAt = System.currentTimeMillis()
            _state.value = InferenceState.Busy(InferenceStepProgress(stepLabel, 0, startedAt))
            try {
                val response = callExternalApiChat(
                    messages = messages,
                    systemInstruction = systemInstructionOverride,
                    maxOutputTokensOverride = maxOutputTokens,
                )
                val tokensCount = response.split(Regex("\\s+")).size
                _state.value = InferenceState.Busy(InferenceStepProgress(stepLabel, tokensCount, startedAt))
                onChunk(response)
                consecutiveFailures = 0
            } catch (t: Throwable) {
                Log.w(TAG, "Inference failed in step $stepLabel: ${t.message}", t)
                consecutiveFailures = 1
                throw t
            } finally {
                if (_state.value is InferenceState.Busy) {
                    _state.value = InferenceState.Ready(currentBackendLabel)
                }
            }
        }
    }

    private suspend fun reinitializeInternal(): Boolean {
        val ctx = initContext ?: return false
        val cfg = currentConfig ?: return false
        val strategy = currentStrategy
        close()
        return try {
            initialize(ctx, cfg, strategy)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "reinitializeInternal() failed", t)
            false
        }
    }

    suspend fun probe(prompt: String): String = withContext(Dispatchers.IO) {
        val eng = engine
        if (eng != null) {
            return@withContext probeLocal(prompt, eng)
        }

        require(_state.value is InferenceState.Ready) {
            "Engine not READY (state=${_state.value}). Call initialize() first."
        }
        mutex.withLock {
            val startedAt = System.currentTimeMillis()
            _state.value = InferenceState.Busy(InferenceStepProgress("probe", 0, startedAt))
            try {
                callExternalApi(prompt, systemInstruction = PROBE_SYSTEM_INSTRUCTION)
            } finally {
                if (_state.value is InferenceState.Busy) {
                    _state.value = InferenceState.Ready(currentBackendLabel)
                }
            }
        }
    }

    private suspend fun probeLocal(prompt: String, eng: Engine): String {
        val cfg = currentConfig ?: InferenceConfig()
        return mutex.withLock {
            _state.value = InferenceState.Busy(InferenceStepProgress("probe", 0))
            val probeConv = try {
                eng.createConversation(buildProbeConfig(cfg))
            } catch (t: Throwable) {
                _state.value = InferenceState.Ready(currentBackendLabel)
                throw t
            }
            try {
                probeConv.sendMessage(prompt).toString()
            } finally {
                runCatching { probeConv.close() }
                    .onFailure { Log.w(TAG, "probeConv.close() failed", it) }
                if (_state.value is InferenceState.Busy) {
                    _state.value = InferenceState.Ready(currentBackendLabel)
                }
            }
        }
    }

    fun probeStreaming(prompt: String): Flow<String> = flow {
        require(_state.value is InferenceState.Ready) {
            "Engine not READY (state=${_state.value}). Call initialize() first."
        }
        val response = probe(prompt)
        emit(response)
    }.flowOn(Dispatchers.IO)

    private fun getSavedSettings(context: Context): ExternalLlmSettings {
        val prefs = context.getSharedPreferences("spendai_settings", Context.MODE_PRIVATE)
        val provider = prefs.getString("llm_provider", "GEMINI") ?: "GEMINI"
        val apiKey = (prefs.getString("llm_api_key", "") ?: "").ifEmpty { prefs.getString("gemini_api_key", "") ?: "" }
        val model = prefs.getString("llm_model", "gemma-4-31b-it") ?: "gemma-4-31b-it"
        val baseUrl = prefs.getString("llm_base_url", "") ?: ""
        return ExternalLlmSettings(provider, apiKey, model, baseUrl)
    }

    private suspend fun callExternalApi(
        prompt: String,
        systemInstruction: String? = null,
        maxOutputTokensOverride: Int? = null,
    ): String = withContext(Dispatchers.IO) {
        val ctx = initContext ?: throw IOException("GemmaInferenceEngine not initialized")
        val settings = getSavedSettings(ctx)
        
        if (settings.provider != "OLLAMA" && settings.apiKey.isEmpty()) {
            throw IOException("${settings.provider} API Key is not set. Please configure it in model settings.")
        }

        val effectiveMaxOutputTokens = maxOutputTokensOverride ?: (currentConfig?.maxTokens ?: 32768)
        val mediaType = "application/json; charset=utf-8".toMediaType()

        val request = when (settings.provider) {
            "GEMINI" -> {
                val requestBodyJson = buildJsonObject {
                    if (systemInstruction != null) {
                        putJsonObject("systemInstruction") {
                            putJsonArray("parts") {
                                addJsonObject {
                                    put("text", systemInstruction)
                                }
                            }
                        }
                    }
                    putJsonArray("contents") {
                        addJsonObject {
                            putJsonArray("parts") {
                                addJsonObject {
                                    put("text", prompt)
                                }
                            }
                        }
                    }
                    putJsonObject("generationConfig") {
                        put("temperature", 0.2)
                        put("maxOutputTokens", effectiveMaxOutputTokens)
                    }
                }
                
                val modelName = settings.model.ifEmpty { "gemma-4-31b-it" }
                Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=${settings.apiKey}")
                    .post(requestBodyJson.toString().toRequestBody(mediaType))
                    .build()
            }
            "CLAUDE" -> {
                val requestBodyJson = buildJsonObject {
                    put("model", settings.model.ifEmpty { "claude-3-5-sonnet" })
                    if (systemInstruction != null) {
                        put("system", systemInstruction)
                    }
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "user")
                            put("content", prompt)
                        }
                    }
                    put("temperature", 0.2)
                    put("max_tokens", effectiveMaxOutputTokens)
                }
                Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .header("x-api-key", settings.apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .post(requestBodyJson.toString().toRequestBody(mediaType))
                    .build()
            }
            else -> { // OpenAI, Kimi, Zhipu/Zai, Ollama, etc. (OpenAI-compatible)
                val requestBodyJson = buildJsonObject {
                    put("model", settings.model)
                    putJsonArray("messages") {
                        if (systemInstruction != null) {
                            addJsonObject {
                                put("role", "system")
                                put("content", systemInstruction)
                            }
                        }
                        addJsonObject {
                            put("role", "user")
                            put("content", prompt)
                        }
                    }
                    put("temperature", 0.2)
                    put("max_tokens", effectiveMaxOutputTokens)
                }
                val defaultUrl = when (settings.provider) {
                    "OPENAI" -> "https://api.openai.com/v1/chat/completions"
                    "KIMI" -> "https://api.moonshot.cn/v1/chat/completions"
                    "ZHIPU" -> "https://open.bigmodel.cn/api/paas/v4/chat/completions"
                    "OLLAMA" -> "http://10.0.2.2:11434/v1/chat/completions"
                    else -> "https://api.openai.com/v1/chat/completions"
                }
                val url = settings.baseUrl.ifEmpty { defaultUrl }
                val builder = Request.Builder()
                    .url(url)
                    .post(requestBodyJson.toString().toRequestBody(mediaType))
                
                if (settings.apiKey.isNotEmpty()) {
                    builder.header("Authorization", "Bearer ${settings.apiKey}")
                }
                builder.build()
            }
        }

        var attempt = 0
        val maxAttempts = 10
        while (attempt < maxAttempts) {
            attempt++
            var responseCode = 0
            var errorMsg = ""
            var success = false
            var responseBody: String? = null

            try {
                httpClient.newCall(request).execute().use { response ->
                    responseCode = response.code
                    if (response.isSuccessful) {
                        responseBody = response.body?.string()
                        success = true
                    } else {
                        errorMsg = response.body?.string() ?: ""
                    }
                }
            } catch (ioe: IOException) {
                if (attempt == maxAttempts) throw ioe
                Log.w(TAG, "Network error on attempt $attempt; retrying in 5 seconds...", ioe)
                kotlinx.coroutines.delay(5000)
                continue
            }

            if (success && responseBody != null) {
                val element = Json.parseToJsonElement(responseBody!!)
                val text = when (settings.provider) {
                    "GEMINI" -> {
                        val parts = element.jsonObject["candidates"]
                            ?.jsonArray?.getOrNull(0)
                            ?.jsonObject?.get("content")
                            ?.jsonObject?.get("parts")
                            ?.jsonArray
                            ?: throw IOException("Failed to extract parts from Gemini response: $responseBody")

                        val nonThoughtPart = parts.firstOrNull { part ->
                            val thought = part.jsonObject["thought"]?.jsonPrimitive?.content
                            thought != "true"
                        } ?: parts.getOrNull(0)

                        nonThoughtPart?.jsonObject?.get("text")?.jsonPrimitive?.content
                            ?: throw IOException("Failed to extract text from Gemini response: $responseBody")
                    }
                    "CLAUDE" -> {
                        element.jsonObject["content"]
                            ?.jsonArray?.getOrNull(0)
                            ?.jsonObject?.get("text")?.jsonPrimitive?.content
                            ?: throw IOException("Failed to extract text from Claude response: $responseBody")
                    }
                    else -> { // OpenAI-compatible
                        element.jsonObject["choices"]
                            ?.jsonArray?.getOrNull(0)
                            ?.jsonObject?.get("message")
                            ?.jsonObject?.get("content")?.jsonPrimitive?.content
                            ?: throw IOException("Failed to extract text from response: $responseBody")
                    }
                }
                return@withContext text
            }

            if (responseCode in TRANSIENT_RETRY_CODES) {
                Log.w(
                    TAG,
                    "Transient error ($responseCode) on attempt $attempt/$maxAttempts. " +
                        "Waiting 60 seconds before retry...",
                )
                kotlinx.coroutines.delay(60_000)
            } else {
                throw IOException("${settings.provider} API call failed with code $responseCode: $errorMsg")
            }
        }
        throw IOException("API call failed: Max rate limit retries ($maxAttempts) exceeded")
    }

    /**
     * Multi-turn counterpart of [callExternalApi]. Builds the
     * per-provider request body for a chat-completions call,
     * runs the same retry / backoff / thought-filtering loop as
     * the single-turn path, and returns the streamed response
     * text.
     *
     * System messages in [messages] are extracted and routed to
     * the provider's system field where the provider supports
     * one (Gemini, Claude, OpenAI). [systemInstruction] (the
     * override supplied by the caller) wins over any system
     * message in the list.
     *
     * Tool messages are translated per-provider:
     *  - OpenAI / Ollama / Kimi / Zhipu: real "tool" role with
     *    [ChatMessage.name] as the function name.
     *  - Gemini: a "functionResponse" part inside a "user" turn.
     *  - Claude: a "user" turn prefixed with "Tool result: ...".
     */
    private suspend fun callExternalApiChat(
        messages: List<ChatMessage>,
        systemInstruction: String? = null,
        maxOutputTokensOverride: Int? = null,
    ): String = withContext(Dispatchers.IO) {
        val ctx = initContext ?: throw IOException("GemmaInferenceEngine not initialized")
        val settings = getSavedSettings(ctx)

        if (settings.provider != "OLLAMA" && settings.apiKey.isEmpty()) {
            throw IOException("${settings.provider} API Key is not set. Please configure it in model settings.")
        }

        val effectiveMaxOutputTokens = maxOutputTokensOverride ?: (currentConfig?.maxTokens ?: 32768)
        val mediaType = "application/json; charset=utf-8".toMediaType()

        val effectiveSystem = systemInstruction
            ?: messages.firstOrNull { it.role == ChatMessage.ROLE_SYSTEM }?.content
        val conversation = messages.filter { it.role != ChatMessage.ROLE_SYSTEM }

        val request = when (settings.provider) {
            "GEMINI" -> buildGeminiChatRequest(
                settings = settings,
                systemInstruction = effectiveSystem,
                messages = conversation,
                mediaType = mediaType,
                maxOutputTokens = effectiveMaxOutputTokens,
            )
            "CLAUDE" -> buildClaudeChatRequest(
                settings = settings,
                systemInstruction = effectiveSystem,
                messages = conversation,
                mediaType = mediaType,
                maxOutputTokens = effectiveMaxOutputTokens,
            )
            else -> buildOpenAiChatRequest(
                settings = settings,
                systemInstruction = effectiveSystem,
                messages = conversation,
                mediaType = mediaType,
                maxOutputTokens = effectiveMaxOutputTokens,
            )
        }

        // Retry / backoff loop mirrors callExternalApi. We keep
        // the loop body inside the chat-specific method instead
        // of refactoring the original so the two paths stay
        // readable in isolation.
        var attempt = 0
        val maxAttempts = 10
        while (attempt < maxAttempts) {
            attempt++
            var responseCode = 0
            var errorMsg = ""
            var success = false
            var responseBody: String? = null

            try {
                httpClient.newCall(request).execute().use { response ->
                    responseCode = response.code
                    if (response.isSuccessful) {
                        responseBody = response.body?.string()
                        success = true
                    } else {
                        errorMsg = response.body?.string() ?: ""
                    }
                }
            } catch (ioe: IOException) {
                if (attempt == maxAttempts) throw ioe
                Log.w(TAG, "Network error on attempt $attempt; retrying in 5 seconds...", ioe)
                kotlinx.coroutines.delay(5000)
                continue
            }

            if (success && responseBody != null) {
                val element = Json.parseToJsonElement(responseBody!!)
                val text = when (settings.provider) {
                    "GEMINI" -> {
                        val parts = element.jsonObject["candidates"]
                            ?.jsonArray?.getOrNull(0)
                            ?.jsonObject?.get("content")
                            ?.jsonObject?.get("parts")
                            ?.jsonArray
                            ?: throw IOException("Failed to extract parts from Gemini response: $responseBody")

                        val nonThoughtPart = parts.firstOrNull { part ->
                            val thought = part.jsonObject["thought"]?.jsonPrimitive?.content
                            thought != "true"
                        } ?: parts.getOrNull(0)

                        nonThoughtPart?.jsonObject?.get("text")?.jsonPrimitive?.content
                            ?: throw IOException("Failed to extract text from Gemini response: $responseBody")
                    }
                    "CLAUDE" -> {
                        element.jsonObject["content"]
                            ?.jsonArray?.getOrNull(0)
                            ?.jsonObject?.get("text")?.jsonPrimitive?.content
                            ?: throw IOException("Failed to extract text from Claude response: $responseBody")
                    }
                    else -> { // OpenAI-compatible
                        element.jsonObject["choices"]
                            ?.jsonArray?.getOrNull(0)
                            ?.jsonObject?.get("message")
                            ?.jsonObject?.get("content")?.jsonPrimitive?.content
                            ?: throw IOException("Failed to extract text from response: $responseBody")
                    }
                }
                return@withContext text
            }

            if (responseCode in TRANSIENT_RETRY_CODES) {
                Log.w(
                    TAG,
                    "Transient error ($responseCode) on attempt $attempt/$maxAttempts. " +
                        "Waiting 60 seconds before retry...",
                )
                kotlinx.coroutines.delay(60_000)
            } else {
                throw IOException("${settings.provider} API call failed with code $responseCode: $errorMsg")
            }
        }
        throw IOException("API call failed: Max rate limit retries ($maxAttempts) exceeded")
    }

    /**
     * Build a Gemini [Request] from a multi-turn [conversation].
     * System instruction goes in the top-level field. User /
     * assistant turns are mapped to "user" / "model" roles. Tool
     * turns become a "user" turn with a "functionResponse" part
     * so the model can read the tool output.
     */
    private fun buildGeminiChatRequest(
        settings: ExternalLlmSettings,
        systemInstruction: String?,
        messages: List<ChatMessage>,
        mediaType: okhttp3.MediaType,
        maxOutputTokens: Int,
    ): Request {
        val requestBodyJson = buildJsonObject {
            if (systemInstruction != null) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        addJsonObject { put("text", systemInstruction) }
                    }
                }
            }
            putJsonArray("contents") {
                messages.forEach { msg ->
                    val (geminiRole, parts) = when (msg.role) {
                        ChatMessage.ROLE_ASSISTANT -> "model" to listOf(buildJsonObject {
                            put("text", msg.content)
                        })
                        ChatMessage.ROLE_TOOL -> "user" to listOf(buildJsonObject {
                            putJsonObject("functionResponse") {
                                put("name", msg.name ?: "tool")
                                putJsonObject("response") {
                                    put("output", msg.content)
                                }
                            }
                        })
                        else -> "user" to listOf(buildJsonObject {
                            put("text", msg.content)
                        })
                    }
                    addJsonObject {
                        put("role", geminiRole)
                        putJsonArray("parts") {
                            parts.forEach { part -> add(part) }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.2)
                put("maxOutputTokens", maxOutputTokens)
            }
        }
        val modelName = settings.model.ifEmpty { "gemma-4-31b-it" }
        return Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=${settings.apiKey}")
            .post(requestBodyJson.toString().toRequestBody(mediaType))
            .build()
    }

    /**
     * Build a Claude [Request] from a multi-turn [conversation].
     * System is top-level. Claude requires alternating user /
     * assistant turns; consecutive same-role turns are merged
     * with a blank line separator. Tool results are folded into
     * a "user" turn with a "Tool result:" prefix.
     */
    private fun buildClaudeChatRequest(
        settings: ExternalLlmSettings,
        systemInstruction: String?,
        messages: List<ChatMessage>,
        mediaType: okhttp3.MediaType,
        maxOutputTokens: Int,
    ): Request {
        val normalized = mergeConsecutiveRoles(messages).map { msg ->
            when (msg.role) {
                ChatMessage.ROLE_TOOL -> msg.copy(
                    role = ChatMessage.ROLE_USER,
                    content = "Tool result (${msg.name ?: "tool"}):\n${msg.content}",
                )
                else -> msg
            }
        }
        val requestBodyJson = buildJsonObject {
            put("model", settings.model.ifEmpty { "claude-3-5-sonnet" })
            if (systemInstruction != null) {
                put("system", systemInstruction)
            }
            putJsonArray("messages") {
                normalized.forEach { msg ->
                    addJsonObject {
                        put("role", msg.role)
                        put("content", msg.content)
                    }
                }
            }
            put("temperature", 0.2)
            put("max_tokens", maxOutputTokens)
        }
        return Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", settings.apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(requestBodyJson.toString().toRequestBody(mediaType))
            .build()
    }

    /**
     * Build an OpenAI-compatible [Request] from a multi-turn
     * [conversation]. System is the first message. Tool messages
     * use the real "tool" role with [ChatMessage.name] as the
     * function name. Consecutive same-role turns are merged so
     * the request is well-formed.
     */
    private fun buildOpenAiChatRequest(
        settings: ExternalLlmSettings,
        systemInstruction: String?,
        messages: List<ChatMessage>,
        mediaType: okhttp3.MediaType,
        maxOutputTokens: Int,
    ): Request {
        val withOptionalSystem = buildList {
            if (systemInstruction != null) {
                add(ChatMessage(ChatMessage.ROLE_SYSTEM, systemInstruction))
            }
            addAll(messages)
        }
        val normalized = mergeConsecutiveRoles(withOptionalSystem)
        val requestBodyJson = buildJsonObject {
            put("model", settings.model)
            putJsonArray("messages") {
                normalized.forEach { msg ->
                    addJsonObject {
                        put("role", msg.role)
                        put("content", msg.content)
                        if (msg.role == ChatMessage.ROLE_TOOL && msg.name != null) {
                            put("name", msg.name)
                        }
                    }
                }
            }
            put("temperature", 0.2)
            put("max_tokens", maxOutputTokens)
        }
        val defaultUrl = when (settings.provider) {
            "OPENAI" -> "https://api.openai.com/v1/chat/completions"
            "KIMI" -> "https://api.moonshot.cn/v1/chat/completions"
            "ZHIPU" -> "https://open.bigmodel.cn/api/paas/v4/chat/completions"
            "OLLAMA" -> "http://10.0.2.2:11434/v1/chat/completions"
            else -> "https://api.openai.com/v1/chat/completions"
        }
        val url = settings.baseUrl.ifEmpty { defaultUrl }
        val builder = Request.Builder()
            .url(url)
            .post(requestBodyJson.toString().toRequestBody(mediaType))
        if (settings.apiKey.isNotEmpty()) {
            builder.header("Authorization", "Bearer ${settings.apiKey}")
        }
        return builder.build()
    }

    /**
     * Merge consecutive messages with the same role into a
     * single message with a blank-line separated body. Required
     * for providers (Claude, OpenAI) that reject same-role
     * adjacency. Assistant turns are never merged with each
     * other to preserve the model's own demarcation.
     */
    private fun mergeConsecutiveRoles(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        val out = mutableListOf<ChatMessage>()
        for (msg in messages) {
            val last = out.lastOrNull()
            if (last != null && last.role == msg.role && msg.role != ChatMessage.ROLE_ASSISTANT) {
                out[out.lastIndex] = last.copy(
                    content = last.content + "\n\n" + msg.content,
                    name = last.name ?: msg.name,
                )
            } else {
                out.add(msg)
            }
        }
        return out
    }

    private fun buildProbeConfig(config: InferenceConfig) = ConversationConfig(
        systemInstruction = Contents.of(PROBE_SYSTEM_INSTRUCTION),
        samplerConfig = SamplerConfig(
            topK = config.topK,
            topP = config.topP.toDouble(),
            temperature = config.temperature.toDouble(),
            seed = 0,
        ),
    )

    private fun buildSessionConfig(config: InferenceConfig) = SessionConfig(
        samplerConfig = SamplerConfig(
            topK = config.topK,
            topP = config.topP.toDouble(),
            temperature = config.temperature.toDouble(),
            seed = 0,
        ),
    )

    fun close() {
        runCatching { currentSession?.close() }
            .onFailure { Log.w(TAG, "currentSession.close() failed", it) }
        currentSession = null
        runCatching { engine?.close() }
            .onFailure { Log.w(TAG, "engine.close() failed", it) }
        engine = null
        currentBackendLabel = ""
        consecutiveFailures = 0
        if (_state.value !is InferenceState.Error) {
            _state.value = InferenceState.Uninitialized
        }
        Log.d(TAG, "Engine closed (config preserved for reinit)")
    }

    fun shutdown() {
        close()
        currentConfig = null
        initContext = null
        sessionConfig = null
        Log.d(TAG, "Engine shutdown (config dropped)")
    }

    suspend fun reinitialize(): Boolean = withContext(Dispatchers.IO) {
        reinitializeInternal()
    }

    internal fun setReadyForTest(
        testEngine: Engine,
        backendLabel: String = "NPU",
        config: InferenceConfig = InferenceConfig(),
    ) {
        engine = testEngine
        currentBackendLabel = backendLabel
        currentConfig = config
        sessionConfig = buildSessionConfig(config)
        _state.value = InferenceState.Ready(backendLabel)
    }

    private fun strategyFor(config: InferenceConfig): BackendStrategy =
        when (config.preferredBackend) {
            com.spendai.app.inference.PreferredBackend.NPU -> BackendStrategy.NpuFirst
            com.spendai.app.inference.PreferredBackend.GPU -> BackendStrategy.GpuFirst
            com.spendai.app.inference.PreferredBackend.CPU -> BackendStrategy.CpuOnly
        }

    private companion object {
        const val TAG = "GemmaInferenceEngine"

        /**
         * HTTP codes that signal a transient infrastructure error
         * and warrant a long backoff + retry:
         *  - 429 Too Many Requests
         *  - 500 Internal Server Error
         *  - 502 Bad Gateway
         *  - 503 Service Unavailable
         *  - 504 Gateway Timeout
         * 4xx client errors (400, 401, 403, 404) are not retried —
         * they would fail the same way on the next attempt.
         */
        val TRANSIENT_RETRY_CODES: Set<Int> = setOf(429, 500, 502, 503, 504)

        const val SYSTEM_INSTRUCTION =
            "You are a private, on-device financial SMS parser. " +
                "Extract transaction details (amount, currency, merchant, timestamp) " +
                "as a JSON object. If the message is not financial, return " +
                "{\"kind\":\"ignore\"}."

        const val PROBE_SYSTEM_INSTRUCTION =
            "You are a helpful assistant. Follow the user\'s instructions exactly."
    }
}

data class ExternalLlmSettings(
    val provider: String,
    val apiKey: String,
    val model: String,
    val baseUrl: String
)
