package eu.kanade.tachiyomi.ui.reader.colorize

import android.content.Context
import android.net.Uri
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.core.common.util.lang.withIOContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

enum class ModelKind(val minBytes: Long) {
    COLORIZER(1L * 1024 * 1024),
}

object ColorizerModelInstaller {

    private const val BUFFER_SIZE = 64 * 1024

    fun normalizeUrl(url: String): String = url.trim().replace("/blob/", "/resolve/")

    fun modelFile(context: Context, kind: ModelKind): File = when (kind) {
        ModelKind.COLORIZER -> PageColorizer.modelFile(context)
    }

    fun isInstalled(context: Context, kind: ModelKind = ModelKind.COLORIZER): Boolean =
        modelFile(context, kind).length() > 0

    fun remove(context: Context, kind: ModelKind = ModelKind.COLORIZER) {
        modelFile(context, kind).delete()
    }

    suspend fun download(context: Context, url: String, onProgress: (Float) -> Unit = {}) =
        downloadModel(context, ModelKind.COLORIZER, url, onProgress)

    suspend fun import(context: Context, uri: Uri) = importModel(context, ModelKind.COLORIZER, uri)

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

    private fun install(context: Context, kind: ModelKind, temp: File) {
        if (temp.length() < kind.minBytes) throw IOException("The file is too small to be the model")
        try {
            when (kind) {
                ModelKind.COLORIZER -> MangaColorizer(temp).close()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Model installation validation failed for ${kind.name}" }
            throw IOException("This is not a valid ${kind.name.lowercase()} model: ${e.message}", e)
        }
        val target = modelFile(context, kind)
        target.parentFile?.mkdirs()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
        }
        logcat(LogPriority.DEBUG) { "Model ${kind.name} successfully installed and verified." }
    }
}
