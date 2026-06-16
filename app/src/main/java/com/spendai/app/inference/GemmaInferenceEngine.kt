package com.spendai.app.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Public state of the engine. Exposed as a [StateFlow] so the UI
 * layer (Phase 2) can render a "model is ready / busy / failed" badge
 * without polling.
 */
sealed interface InferenceState {
    data object Uninitialized : InferenceState
    data object Loading : InferenceState
    data object Ready : InferenceState
    data object Busy : InferenceState
    data class Error(val message: String, val cause: Throwable? = null) : InferenceState
}

/**
 * Thin Kotlin wrapper around the Google AI Edge LiteRT-LM
 * (`com.google.ai.edge.litertlm`) runtime, configured for the
 * Gemma 4 E2B IT model.
 *
 * ## Why a wrapper
 *
 * The raw `Engine` / `Conversation` API is fine, but it leaks three
 * concerns into every call site:
 *  1. **Backend fallback** — NPU may fail, GPU may fail, CPU is the floor.
 *  2. **Lifecycle** — `Engine` and `Conversation` are `AutoCloseable` and
 *     hold large native buffers. We need a single owner.
 *  3. **Threading** — the engine is NOT safe for concurrent calls. We
 *     guard it with a [Mutex].
 *
 * This class hides all three.
 *
 * ## MTP and ordering
 *
 * `ExperimentalFlags.enableSpeculativeDecoding` is a GLOBAL static.
 * Setting it after `Engine(…)` is constructed has no effect on the
 * already-built engine, so we flip it before the first `Engine` is
 * instantiated. On the GPU backend this yields up to 2.2x decode
 * speedup (per Google's Gemma 4 perf page).
 *
 * ## Hardware-accelerated backend notes
 *
 * The `Backend.GPU()` path uses OpenCL / ML Drift under the hood and
 * needs the `<uses-native-library>` declarations for `libvndksupport.so`
 * and `libOpenCL.so` in the manifest (already in place). The
 * `Backend.NPU(nativeLibraryDir = …)` path needs the QNN
 * `libQnnHtp.so` / `libQnnSystem.so` binaries to be present in
 * `applicationInfo.nativeLibraryDir`; if they are missing the JNI
 * bridge throws `LiteRtLmJniException` with a `TF_LITE_AUX not found`
 * message and we fall through to the next backend in the chain.
 */
class GemmaInferenceEngine {

    private val _state = MutableStateFlow<InferenceState>(InferenceState.Uninitialized)
    val state: StateFlow<InferenceState> = _state.asStateFlow()

    // The native engine is NOT safe for concurrent calls; this serialises
    // every operation that touches it.
    private val mutex = Mutex()

    // Lazily allocated; the actual instance depends on the chosen
    // backend and is not known until [initialize] succeeds.
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var currentConfig: InferenceConfig? = null

    /**
     * Bring the engine up. Idempotent — calling it twice with the
     * same config is a no-op. Different configs are not supported
     * in Phase 1; call [close] first.
     *
     * Can take up to 10 seconds on first run (model load) — call from
     * a background coroutine.
     */
    suspend fun initialize(
        context: Context,
        config: InferenceConfig = InferenceConfig(),
        strategy: BackendStrategy = BackendStrategy.NpuFirst
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

        // EXPERIMENTAL: speculative decoding. MUST be set before Engine
        // is constructed — it's a global static that the engine reads
        // at construction time. On GPU backends this is essentially
        // free; on CPU it is workload-dependent (per Gemma 4 perf
        // page) and can slightly slow freeform generation. We let the
        // caller opt out via [InferenceConfig.enableMtp].
        @OptIn(ExperimentalApi::class)
        ExperimentalFlags.enableSpeculativeDecoding = config.enableMtp

        val modelFile = ModelInstaller.ensureModelInstalled(context, config.modelFileName)
        val nativeLibDir = { context.applicationInfo?.nativeLibraryDir }
        val cacheDir = config.cacheDir ?: context.cacheDir.path

        var lastError: Throwable? = null
        for (buildBackend in strategy.candidates(nativeLibDir)) {
            val backend = try {
                buildBackend()
            } catch (t: Throwable) {
                Log.w(TAG, "Backend construction failed: ${t.message}")
                lastError = t
                continue
            }

            try {
                Log.i(TAG, "Trying backend: ${backend.javaClass.simpleName}")
                val engineConfig = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                    cacheDir = cacheDir
                )
                val newEngine = Engine(engineConfig)
                newEngine.initialize()  // up to 10s on first run
                attachConversation(newEngine, config)
                engine = newEngine
                _state.value = InferenceState.Ready
                Log.i(TAG, "Engine READY on ${backend.javaClass.simpleName}")
                return@withContext
            } catch (e: LiteRtLmJniException) {
                Log.w(TAG, "Backend ${backend.javaClass.simpleName} failed: ${e.message}")
                lastError = e
                // fall through to next backend
            } catch (t: Throwable) {
                Log.e(TAG, "Unexpected error during engine init", t)
                lastError = t
            }
        }

        val msg = "No backend could initialise the model. Last error: ${lastError?.message}"
        _state.value = InferenceState.Error(msg, lastError)
        throw LiteRtLmJniException(msg)
    }

    /**
     * Synchronous one-shot generation. The model produces a complete
     * response and we return the joined text. For streaming, use
     * [generatePredictionStreaming].
     */
    suspend fun generatePrediction(prompt: String): String = withContext(Dispatchers.IO) {
        require(_state.value is InferenceState.Ready) {
            "Engine not READY (state=${_state.value}). Call initialize() first."
        }
        mutex.withLock {
            _state.value = InferenceState.Busy
            try {
                val conv = conversation ?: error("Conversation was null after READY state")
                val response: Message = conv.sendMessage(prompt)
                // Phase 1: we take the model's textual representation
                // directly. The proper path is to walk `response.contents`
                // and pull out the Text content blocks, but for the
                // skeleton any non-empty output is enough to prove the
                // round-trip works.
                response.toString()
            } finally {
                _state.value = InferenceState.Ready
            }
        }
    }

    /**
     * Streaming variant. Emits each chunk of generated text as the
     * model produces it. Preferred for any future UI surface.
     */
    fun generatePredictionStreaming(prompt: String): Flow<String> = flow {
        require(_state.value is InferenceState.Ready) {
            "Engine not READY (state=${_state.value}). Call initialize() first."
        }
        mutex.withLock {
            _state.value = InferenceState.Busy
            try {
                val conv = conversation ?: return@flow
                conv.sendMessageAsync(prompt).collect { chunk -> emit(chunk.toString()) }
            } finally {
                _state.value = InferenceState.Ready
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * One-shot probe that runs [prompt] against a fresh conversation
     * with a neutral system instruction, then returns the joined
     * reply. The production SMS-parser persona (and its
     * `{ "kind": "ignore" }` fallback) is NOT applied here, so a probe
     * prompt that asks the model to say a specific string will
     * actually elicit that string.
     *
     * The probe conversation is created on demand and closed after
     * the call; the long-lived [conversation] used by
     * [generatePrediction] is untouched.
     */
    suspend fun probe(prompt: String): String = withContext(Dispatchers.IO) {
        require(_state.value is InferenceState.Ready) {
            "Engine not READY (state=${_state.value}). Call initialize() first."
        }
        val cfg = currentConfig ?: InferenceConfig()
        mutex.withLock {
            _state.value = InferenceState.Busy
            val probeConv = try {
                engine!!.createConversation(buildProbeConfig(cfg))
            } catch (t: Throwable) {
                _state.value = InferenceState.Ready
                throw t
            }
            try {
                probeConv.sendMessage(prompt).toString()
            } finally {
                runCatching { probeConv.close() }
                    .onFailure { Log.w(TAG, "probeConv.close() failed", it) }
                _state.value = InferenceState.Ready
            }
        }
    }

    /**
     * Streaming variant of [probe]. Emits each chunk as the model
     * produces it so the UI can show progressive text.
     */
    fun probeStreaming(prompt: String): Flow<String> = flow {
        require(_state.value is InferenceState.Ready) {
            "Engine not READY (state=${_state.value}). Call initialize() first."
        }
        val cfg = currentConfig ?: InferenceConfig()
        mutex.withLock {
            _state.value = InferenceState.Busy
            val probeConv = try {
                engine!!.createConversation(buildProbeConfig(cfg))
            } catch (t: Throwable) {
                _state.value = InferenceState.Ready
                throw t
            }
            try {
                probeConv.sendMessageAsync(prompt).collect { chunk -> emit(chunk.toString()) }
            } finally {
                runCatching { probeConv.close() }
                    .onFailure { Log.w(TAG, "probeConv.close() failed", it) }
                _state.value = InferenceState.Ready
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun buildProbeConfig(config: InferenceConfig) = ConversationConfig(
        systemInstruction = Contents.of(PROBE_SYSTEM_INSTRUCTION),
        samplerConfig = SamplerConfig(
            topK = config.topK,
            topP = config.topP.toDouble(),
            temperature = config.temperature.toDouble(),
            seed = 0,
        ),
    )

    /**
     * Releases the engine and conversation. Idempotent. After close,
     * the engine must be re-initialized before further use.
     */
    fun close() {
        runCatching { conversation?.close() }
            .onFailure { Log.w(TAG, "conversation.close() failed", it) }
        runCatching { engine?.close() }
            .onFailure { Log.w(TAG, "engine.close() failed", it) }
        conversation = null
        engine = null
        if (_state.value !is InferenceState.Error) {
            _state.value = InferenceState.Uninitialized
        }
    }

    private fun attachConversation(engine: Engine, config: InferenceConfig) {
        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
            samplerConfig = SamplerConfig(
                topK = config.topK,
                topP = config.topP.toDouble(),
                temperature = config.temperature.toDouble(),
                seed = 0
            )
        )
        conversation = engine.createConversation(conversationConfig)
    }

    private companion object {
        const val TAG = "GemmaInferenceEngine"

        // Phase 1 placeholder. Phase 2 will tighten this into a strict
        // JSON-schema prompt that asks the model to return
        // `{ "amount": 123.45, "currency": "INR", "merchant": "...", "ts": 1234567890 }`.
        const val SYSTEM_INSTRUCTION =
            "You are a private, on-device financial SMS parser. " +
                "Extract transaction details (amount, currency, merchant, timestamp) " +
                "as a JSON object. If the message is not financial, return " +
                "{\"kind\":\"ignore\"}."

        // System instruction used by the standalone "I'm online"
        // probe on the onboarding test screen. Deliberately neutral
        // so the model follows the literal user prompt instead of
        // emitting the SMS parser's ignore sentinel.
        const val PROBE_SYSTEM_INSTRUCTION =
            "You are a helpful assistant. Follow the user\'s instructions exactly."
    }
}
