package com.spendai.app.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/**
 * Resolves the on-disk path of the Gemma 4 E2B `.litertlm` model.
 *
 * ## Resolution order
 *
 *  1. `$filesDir/models/<modelFileName>` — the production path. Users
 *     sideload via
 *       `adb push gemma-4-e2b-it.litertlm /sdcard/Android/data/com.spendai.app/files/models/`
 *     and we read from there. The file lives in the app's private
 *     external dir so the user can manage it without root.
 *  2. `assets/models/<modelFileName>` — dev-only. Streams the bundled
 *     asset to `$filesDir/models/` so the loader has a real on-disk
 *     file to mmap.
 *  3. Throw [ModelNotFoundException] with a copy-pasteable fix-it hint.
 *
 * The asset path is intentionally NOT the default — the model is 2.58 GB
 * and does not belong in the APK. See `app/src/main/assets/README.md`.
 */
object ModelInstaller {

    private const val TAG = "ModelInstaller"
    private const val MODELS_SUBDIR = "models"

    /**
     * Returns the absolute path of a usable model file. Idempotent —
     * calling it twice does not re-copy the asset.
     */
    suspend fun ensureModelInstalled(
        context: Context,
        modelFileName: String
    ): File = withContext(Dispatchers.IO) {

        val targetDir = File(context.filesDir, MODELS_SUBDIR).apply { mkdirs() }
        val target = File(targetDir, modelFileName)

        if (target.exists() && target.length() > 0L) {
            Log.d(TAG, "Model already staged at ${target.absolutePath} (${target.length() / 1_048_576} MB)")
            return@withContext target
        }

        // Dev-only fallback: if the user dropped the model in
        // app/src/main/assets/models/, copy it into filesDir so the
        // native loader sees a real on-disk file.
        val assetManager = context.assets
        val candidates = assetManager.list(MODELS_SUBDIR) ?: emptyArray()
        if (modelFileName in candidates) {
            Log.i(TAG, "Streaming model from assets to ${target.absolutePath}")
            assetManager.open("$MODELS_SUBDIR/$modelFileName").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return@withContext target
        }

        throw ModelNotFoundException(
            "Model file '$modelFileName' not found. Expected at " +
                "${target.absolutePath} (or in assets/$MODELS_SUBDIR/). " +
                "Sideload with:\n" +
                "  adb push $modelFileName " +
                "/sdcard/Android/data/com.spendai.app/files/models/"
        ).also { Log.e(TAG, it.message, it) }
    }
}

/**
 * Thrown by [ModelInstaller] when neither the filesDir nor the assets
 * path can produce a model. The message includes a working `adb push`
 * command so first-time devs unblock themselves without reading docs.
 */
class ModelNotFoundException(message: String) : FileNotFoundException(message)
