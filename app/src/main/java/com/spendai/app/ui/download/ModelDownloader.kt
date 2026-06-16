package com.spendai.app.ui.download

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * State of a model download.
 */
sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val bytesDownloaded: Long, val totalBytes: Long) : DownloadState
    data object Done : DownloadState
    data class Failed(val message: String, val cause: Throwable? = null) : DownloadState
}

/**
 * Streams the Gemma 4 E2B `.litertlm` artifact from a HuggingFace
 * `resolve/main/...` URL to `$filesDir/models/<filename>`. Writes to a
 * `<name>.litertlm.part` file during the transfer and atomically renames
 * on completion. Resumes a partial download by issuing a `Range:
 * bytes=<existing>-` header.
 *
 * The default [client] uses a 30 s connect timeout and a 60 s read
 * timeout (the latter per socket chunk) and runs on [Dispatchers.IO].
 *
 * @param client OkHttp client to use. Tests inject a MockWebServer-
 *   driven client.
 */
class ModelDownloader(
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun download(
        url: String,
        destination: File,
        onProgress: suspend (DownloadState) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        val parent = destination.parentFile
            ?: return@withContext Result.failure(IOException("Destination has no parent dir: $destination"))
        if (!parent.exists() && !parent.mkdirs()) {
            return@withContext Result.failure(IOException("Cannot create $parent"))
        }
        val partial = File(parent, destination.name + ".part")
        val existing = if (partial.exists()) partial.length() else 0L

        val request = Request.Builder()
            .url(url)
            .apply {
                if (existing > 0L) {
                    header("Range", "bytes=$existing-")
                }
            }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val msg = "HTTP ${response.code} from $url"
                    val state = DownloadState.Failed(msg)
                    onProgress(state)
                    return@withContext Result.failure(IOException(msg))
                }

                val body = response.body
                    ?: return@withContext Result.failure(IOException("Empty body from $url"))

                // Server ignored our Range header and is sending the
                // whole file again. Wipe the partial and start fresh.
                val isFullRestart = existing > 0L && response.code != 206
                if (isFullRestart && partial.exists()) {
                    partial.delete()
                }
                val startOffset = if (isFullRestart) 0L else existing
                val total = if (response.code == 206) {
                    body.contentLength().let { if (it < 0) -1L else it + startOffset }
                } else {
                    body.contentLength()
                }

                body.byteStream().use { input ->
                    // Open in append mode so a 206 (partial-content)
                    // response writes on top of the existing .part
                    // bytes. The fresh-download path (no existing
                    // partial) falls through to truncate-on-open,
                    // which is what we want there.
                    java.io.FileOutputStream(partial, /* append = */ true).use { output ->
                        val buf = ByteArray(BUFFER_SIZE)
                        var downloaded = startOffset
                        var lastEmit = 0L
                        onProgress(DownloadState.Running(downloaded, total))
                        while (true) {
                            val read = input.read(buf)
                            if (read == -1) break
                            if (read > 0) {
                                output.write(buf, 0, read)
                                downloaded += read
                                // Throttle progress emissions to ~10 Hz to
                                // avoid flooding the StateFlow.
                                if (downloaded - lastEmit > PROGRESS_GRANULARITY ||
                                    (total > 0 && downloaded == total)
                                ) {
                                    lastEmit = downloaded
                                    onProgress(DownloadState.Running(downloaded, total))
                                }
                            }
                        }
                        output.flush()
                    }
                }

                if (!partial.renameTo(destination)) {
                    val msg = "Could not rename ${partial.absolutePath} -> ${destination.absolutePath}"
                    onProgress(DownloadState.Failed(msg))
                    return@withContext Result.failure(IOException(msg))
                }

                Log.i(TAG, "Download complete: ${destination.absolutePath} (${destination.length()} bytes)")
                onProgress(DownloadState.Done)
                Result.success(destination)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Download failed", t)
            val msg = t.message ?: t.javaClass.simpleName
            onProgress(DownloadState.Failed(msg, t))
            Result.failure(t)
        }
    }

    companion object {
        private const val TAG = "ModelDownloader"
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_GRANULARITY = 256L * 1024L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * Constants for the Gemma 4 E2B LiteRT-LM artifact hosted on HuggingFace.
 * The path follows HF's resolve convention and resolves to the model
 * file in the [HF_REPO] repo.
 */
object DownloadConfig {
    const val HF_REPO = "litert-community/gemma-4-E2B-it-litert-lm"
    const val HF_FILENAME = "gemma-4-E2B-it.litertlm"
    const val HF_RESOLVE_URL =
        "https://huggingface.co/$HF_REPO/resolve/main/$HF_FILENAME"
}
