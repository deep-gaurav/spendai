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
        runSession(prompt = prompt, stepLabel = "") { chunk -> sb.append(chunk) }
        sb.toString()
    }

    fun generatePredictionTracking(
        prompt: String,
        stepLabel: String,
    ): Flow<String> = flow {
        require(_state.value is InferenceState.Ready) {
            "Engine not READY (state=${_state.value}). Call initialize() first."
        }
        runSession(prompt = prompt, stepLabel = stepLabel) { chunk -> emit(chunk) }
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
        onChunk: suspend (String) -> Unit,
    ) {
        val eng = engine
        if (eng != null) {
            // Local fallback (tests)
            runSessionLocal(prompt, stepLabel, onChunk, eng)
            return
        }

        // Production: Gemini API
        mutex.withLock {
            val startedAt = System.currentTimeMillis()
            _state.value = InferenceState.Busy(InferenceStepProgress(stepLabel, 0, startedAt))
            try {
                val response = callGeminiApi(prompt)
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
                callGeminiApi(prompt, systemInstruction = PROBE_SYSTEM_INSTRUCTION)
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

    private fun getSavedApiKey(context: Context): String {
        val prefs = context.getSharedPreferences("spendai_settings", Context.MODE_PRIVATE)
        return prefs.getString("gemini_api_key", "") ?: ""
    }

    private suspend fun callGeminiApi(
        prompt: String,
        systemInstruction: String? = null
    ): String = withContext(Dispatchers.IO) {
        val ctx = initContext ?: throw IOException("GemmaInferenceEngine not initialized")
        val apiKey = getSavedApiKey(ctx)
        if (apiKey.isEmpty()) {
            throw IOException("Gemini API Key is not set. Please set it in onboarding.")
        }



        val mediaType = "application/json; charset=utf-8".toMediaType()
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
                put("maxOutputTokens", currentConfig?.maxTokens ?: 32768)
            }
        }

        var attempt = 0
        val maxAttempts = 10
        while (attempt < maxAttempts) {
            attempt++
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent?key=$apiKey")
                .post(requestBodyJson.toString().toRequestBody(mediaType))
                .build()

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
                val parts = element.jsonObject["candidates"]
                    ?.jsonArray?.getOrNull(0)
                    ?.jsonObject?.get("content")
                    ?.jsonObject?.get("parts")
                    ?.jsonArray
                    ?: throw IOException("Failed to extract parts from Gemini response: $responseBody")

                // Filter out reasoning/thought blocks if present
                val nonThoughtPart = parts.firstOrNull { part ->
                    val thought = part.jsonObject["thought"]?.jsonPrimitive?.content
                    thought != "true"
                } ?: parts.getOrNull(0)

                val text = nonThoughtPart?.jsonObject?.get("text")?.jsonPrimitive?.content
                    ?: throw IOException("Failed to extract text from Gemini response: $responseBody")

                return@withContext text
            }

            if (responseCode == 429 || responseCode == 503) {
                Log.w(TAG, "Rate limit / service busy ($responseCode) on attempt $attempt/$maxAttempts. Waiting 60 seconds before retry...")
                kotlinx.coroutines.delay(60000)
            } else {
                throw IOException("Gemini API call failed with code $responseCode: $errorMsg")
            }
        }
        throw IOException("Gemini API call failed: Max rate limit retries ($maxAttempts) exceeded")
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

        const val GEMINI_MODEL = "gemma-4-31b-it"

        const val SYSTEM_INSTRUCTION =
            "You are a private, on-device financial SMS parser. " +
                "Extract transaction details (amount, currency, merchant, timestamp) " +
                "as a JSON object. If the message is not financial, return " +
                "{\"kind\":\"ignore\"}."

        const val PROBE_SYSTEM_INSTRUCTION =
            "You are a helpful assistant. Follow the user\'s instructions exactly."
    }
}
