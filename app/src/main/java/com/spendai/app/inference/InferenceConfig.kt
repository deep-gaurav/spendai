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
 * @property maxTokens default output budget for a single generate call.
 *   Matches the Gallery default of 4K (Gemma 4 E2B's context length).
 * @property a2MaxOutputTokens A2's output is a small JSON object
 *   (source / account / merchant candidates). 1K tokens is plenty and
 *   dramatically faster than the 32K default; A2 callers pass this
 *   override via [GemmaInferenceEngine.generatePredictionTracking].
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
 * @property preferredBackend first backend to try. With the manifest
 *   declaring `libedgetpu_litert.so` (Tensor devices) and
 *   `libcdsprpc.so` (Snapdragon) plus the
 *   `play-services-tflite-gpu` dependency, NPU now resolves to a
 *   working hardware backend on every Pixel. The [GemmaInferenceEngine]
 *   may fall through to slower backends per the chosen [BackendStrategy].
 */
data class InferenceConfig(
    val modelFileName: String = BuildConfig.GEMMA_MODEL_FILENAME,
    val cacheDir: String? = null,
    val maxTokens: Int = 32768,
    val a2MaxOutputTokens: Int = 1024,
    val temperature: Float = 0.2f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val enableMtp: Boolean = true,
    val preferredBackend: PreferredBackend = PreferredBackend.GPU
)

/**
 * Coarse-grained preferred backend hint. The engine will use the
 * matching [com.spendai.app.inference.BackendStrategy]:
 *
 * - [NPU] routes to the Google Edge TPU on Tensor devices and to
 *   the QNN NPU on Snapdragon. Tries NPU first, then falls through
 *   to GPU, then CPU.
 * - [GPU] routes to OpenCL/Vulkan. Tries GPU first, then CPU. This
 *   was the original default but is broken on the Pixel 9 Pro Fold
 *   (and likely other Tensor-G2/G3 foldables) — the engine
 *   "initialises" on the Adreno GPU but the
 *   `libLiteRtTopKOpenClSampler.so` / `libLiteRtTopKWebGpuSampler.so`
 *   dispatch libraries are not packaged with the Play Services
 *   TFLite shim, so every inference call is cancelled by the
 *   runtime with `Status Code: 1` (kCancelled) before any tokens
 *   are produced. Use [CPU] on those devices.
 * - [CPU] is the XNNPack floor. Always works, always slow. This is
 *   the new default for the same reason [GPU] is broken on Tensor
 *   foldables.
 */
enum class PreferredBackend { NPU, GPU, CPU }
