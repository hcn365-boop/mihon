package eu.kanade.tachiyomi.ui.reader.colorize

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.core.common.util.lang.withIOContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The two models the reader can use. [minBytes] rejects files that are obviously not the model. */
enum class ModelKind(val minBytes: Long) {
    COLORIZER(1L * 1024 * 1024),
    UPSCALER(10L * 1024),
}

/**
 * Puts the AI models where [PageColorizer] expects them.
 *
 * The models are not bundled with the app (size and licenses: the colorizer weights are CC BY-NC-SA 4.0).
 * The user pastes a link or picks a file, and the file is validated before it replaces anything.
 */
object ColorizerModelInstaller {

    private const val BUFFER_SIZE = 64 * 1024

    /** Hugging Face file pages use /blob/, but the raw file is served from /resolve/. */
    fun normalizeUrl(url: String): String = url.trim().replace("/blob/", "/resolve/")

    fun modelFile(context: Context, kind: ModelKind): File = when (kind) {
        ModelKind.COLORIZER -> PageColorizer.modelFile(context)
        ModelKind.UPSCALER -> PageColorizer.upscalerModelFile(context)
    }

    fun isInstalled(context: Context, kind: ModelKind = ModelKind.COLORIZER): Boolean =
        modelFile(context, kind).length() > 0

    fun remove(context: Context, kind: ModelKind = ModelKind.COLORIZER) {
        modelFile(context, kind).delete()
    }

    /** Colorizer-only versions, kept for the existing settings screen. */
    suspend fun download(context: Context, url: String, onProgress: (Float) -> Unit = {}) =
        downloadModel(context, ModelKind.COLORIZER, url, onProgress)

    suspend fun import(context: Context, uri: Uri) = importModel(context, ModelKind.COLORIZER, uri)

    /**
     * Downloads a model from [url]. Throws [IOException] with a readable message on failure.
     * [onProgress] receives 0..1 when the server reports the file size; it runs on a background thread.
     */
    suspend fun downloadModel(context: Context, kind: ModelKind, url: String, onProgress: (Float) -> Unit = {}) {
        withIOContext {
            val temp = tempFile(context, kind)
            try {
                temp.parentFile?.mkdirs()
                val connection = URL(normalizeUrl(url)).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        throw IOException("Download failed: HTTP ${connection.responseCode}")
                    }
                    if (connection.contentType?.startsWith("text/html") == true) {
                        throw IOException("The link points to a web page, not to the model file")
                    }
                    val total = connection.contentLengthLong
                    connection.inputStream.use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var done = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                if (total > 0) onProgress(done.toFloat() / total)
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                install(context, kind, temp)
            } finally {
                temp.delete()
            }
        }
    }

    /** Copies a model the user picked with the file picker. */
    suspend fun importModel(context: Context, kind: ModelKind, uri: Uri) {
        withIOContext {
            val temp = tempFile(context, kind)
            try {
                temp.parentFile?.mkdirs()
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open the selected file")
                input.use { stream ->
                    temp.outputStream().use { output -> stream.copyTo(output) }
                }
                install(context, kind, temp)
            } finally {
                temp.delete()
            }
        }
    }

    private fun tempFile(context: Context, kind: ModelKind): File {
        val target = modelFile(context, kind)
        return File(target.parentFile, target.name + ".part")
    }

    /** Validates [temp] and moves it into place. A bad file never replaces a working model. */
    private fun install(context: Context, kind: ModelKind, temp: File) {
        if (temp.length() < kind.minBytes) throw IOException("The file is too small to be the model")
        // Opening the model checks its inputs and outputs and throws if they differ from what we expect.
        try {
            when (kind) {
                ModelKind.COLORIZER -> MangaColorizer(temp).close()
                ModelKind.UPSCALER -> Anime4kUpscaler(temp).close()
            }
        } catch (e: Exception) {
            throw IOException("This is not a valid ${kind.name.lowercase()} model: ${e.message}", e)
        }
        val target = modelFile(context, kind)
        target.parentFile?.mkdirs()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
        }
    }
}
