package com.spendai.app.inference

import com.google.ai.edge.litertlm.Backend

/**
 * Ordered chain of backends [GemmaInferenceEngine] will try to bring up.
 * The first one that initialises without throwing wins; the rest are
 * never attempted.
 *
 * The pattern comes from the production Gemma 4 E2B deep-dive
 * published by Google Developer Experts: community Hugging Face builds
 * frequently ship without device-specific QNN binaries, so a hard NPU
 * init throws `LiteRtLmJniException` ("TF_LITE_AUX not found"). Falling
 * through to GPU (OpenCL/ML Drift) or CPU (XNNPack) is the only safe
 * default.
 *
 * The list of [Backend]s is built **lazily** — the closures inside
 * [candidates] are not invoked until the engine actually attempts to
 * spin up that backend. This matters because `Backend.NPU(nativeLibraryDir = …)`
 * reads the app's native library directory at construction time and we
 * don't want that call happening unless we're really about to try NPU.
 */
sealed class BackendStrategy {

    /** Try NPU → GPU → CPU in that order. Default for production. */
    data object NpuFirst : BackendStrategy()

    /** Try GPU → CPU. Use on devices known to lack an NPU delegate. */
    data object GpuFirst : BackendStrategy()

    /** Skip accelerated paths. Useful for tests and extremely old devices. */
    data object CpuOnly : BackendStrategy()

    /**
     * @param nativeLibraryDir Android's
     *   `context.applicationInfo.nativeLibraryDir` for NPU delegate
     *   binaries. Ignored by GPU/CPU backends.
     */
    internal fun candidates(nativeLibraryDir: () -> String?): List<() -> Backend> =
        when (this) {
            is NpuFirst -> listOf(
                { Backend.NPU(nativeLibraryDir = nativeLibraryDir() ?: "") },
                { Backend.GPU() },
                { Backend.CPU() }
            )
            is GpuFirst -> listOf(
                { Backend.GPU() },
                { Backend.CPU() }
            )
            is CpuOnly  -> listOf(
                { Backend.CPU() }
            )
        }
}
