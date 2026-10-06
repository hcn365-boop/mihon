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

/**
 * Puts the Manga Light V6 generator (v6_generator.onnx) where [PageColorizer] expects it.
 *
 * The model is not bundled with the app: its weights are CC BY-NC-SA 4.0 (non-commercial, attribution)
 * and it is about 191 MB. The user pastes a link or picks a file, and the file is validated before use.
 */
object ColorizerModelInstaller {

    private const val MIN_MODEL_BYTES = 1L * 1024 * 1024
    private const val BUFFER_SIZE = 64 * 1024

    /** Hugging Face file pages use /blob/, but the raw file is served from /resolve/. */
    fun normalizeUrl(url: String): String = url.trim().replace("/blob/", "/resolve/")

    fun isInstalled(context: Context): Boolean = PageColorizer.isModelInstalled(context)

    fun remove(context: Context) {
        PageColorizer.modelFile(context).delete()
    }

    /**
     * Downloads the model from [url]. Throws [IOException] with a readable message on failure.
     * [onProgress] receives 0..1 when the server reports the file size; it runs on a background thread.
     */
    suspend fun download(context: Context, url: String, onProgress: (Float) -> Unit = {}) {
        withIOContext {
            val temp = tempFile(context)
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
                install(context, temp)
            } finally {
                temp.delete()
            }
        }
    }

    /** Copies a model the user picked with the file picker. */
    suspend fun import(context: Context, uri: Uri) {
        withIOContext {
            val temp = tempFile(context)
            try {
                temp.parentFile?.mkdirs()
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open the selected file")
                input.use { stream ->
                    temp.outputStream().use { output -> stream.copyTo(output) }
                }
                install(context, temp)
            } finally {
                temp.delete()
            }
        }
    }

    private fun tempFile(context: Context): File =
        File(PageColorizer.modelFile(context).parentFile, "download.part")

    /** Validates [temp] and moves it into place. A bad file never replaces a working model. */
    private fun install(context: Context, temp: File) {
        if (temp.length() < MIN_MODEL_BYTES) throw IOException("The file is too small to be the model")
        // Opening it checks the input and output names of Manga Light V6 and throws if they differ.
        try {
            MangaColorizer(temp).close()
        } catch (e: Exception) {
            throw IOException("This is not a valid Manga Light V6 model: ${e.message}", e)
        }
        val target = PageColorizer.modelFile(context)
        target.parentFile?.mkdirs()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
        }
    }
}
