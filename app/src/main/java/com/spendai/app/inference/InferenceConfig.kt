package com.spendai.app.inference

import com.spendai.app.BuildConfig

/**
 * Static configuration for a [GemmaInferenceEngine] instance.
 *
 * Defaults are tuned for the Gemma 4 E2B IT model loaded from
 * `$filesDir/models/`. Tweak via the secondary constructor in the
 * Application class once a UI exists.
 *
 * @property modelFileName file name expected inside `$filesDir/models/`
 *   (and the legacy assets fallback). Pulled from `BuildConfig` so
 *   instrumentation tests can override it per build flavor.
 * @property cacheDir LiteRT-LM speeds up its second load by writing
 *   compiled artifacts here. Defaults to `context.cacheDir.path`.
 * @property maxTokens maximum tokens the model may emit in one call.
 *   For a 2.58B model on a 4 GB-RAM device, 1024 is generous.
 * @property temperature 0.2f = nearly deterministic. Expense extraction
 *   is structured work, not creative writing — keep this low.
 * @property topK nucleus sampling cutoff.
 * @property topP nucleus sampling cumulative probability cutoff.
 * @property enableMtp when true, sets
 *   [com.google.ai.edge.litertlm.ExperimentalFlags.enableSpeculativeDecoding]
 *   before the engine is constructed. On GPU this gives a 2.2x decode
 *   speedup with no quality loss (per Google's Gemma 4 perf page).
 *   It is a global static and must be flipped BEFORE [com.google.ai.edge.litertlm.Engine]
 *   is instantiated.
 * @property preferredBackend first backend to try. The [GemmaInferenceEngine]
 *   may fall through to slower backends per the chosen [BackendStrategy].
 */
data class InferenceConfig(
    val modelFileName: String = BuildConfig.GEMMA_MODEL_FILENAME,
    val cacheDir: String? = null,
    val maxTokens: Int = 1024,
    val temperature: Float = 0.2f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val enableMtp: Boolean = true,
    val preferredBackend: PreferredBackend = PreferredBackend.GPU
)

/**
 * Coarse-grained preferred backend hint. The engine may still fall through
 * to a different backend if the preferred one fails to initialise
 * (NPU is famously picky on unbranded HF weights).
 */
enum class PreferredBackend { NPU, GPU, CPU }
