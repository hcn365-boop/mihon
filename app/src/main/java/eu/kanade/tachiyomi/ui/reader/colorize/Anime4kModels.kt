package eu.kanade.tachiyomi.ui.reader.colorize

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.core.common.util.lang.withIOContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Anime4K ONNX models (github.com/kato-megumi/anime4kOnnx). The letters are the size of the network:
 * S, M, L, VL, UL, UUL from the fastest to the heaviest. Files are small, so they are downloaded one by
 * one when the user picks them.
 */
object Anime4kModels {

    class Entry(val name: String, val label: String, val scale: Int)

    /** Clean the page without changing its size. "Soft" keeps more of the original texture. */
    val restore = listOf(
        Entry("Restore_S", "Restore S (fastest)", 1),
        Entry("Restore_M", "Restore M", 1),
        Entry("Restore_L", "Restore L", 1),
        Entry("Restore_VL", "Restore VL", 1),
        Entry("Restore_UL", "Restore UL (heaviest)", 1),
        Entry("Restore_Soft_M", "Restore Soft M", 1),
        Entry("Restore_Soft_L", "Restore Soft L", 1),
        Entry("Restore_Soft_VL", "Restore Soft VL", 1),
        Entry("Restore_Soft_UL", "Restore Soft UL", 1),
    )

    /** Make the page larger: plain, with denoising, or the GAN models (x2, x3, x4). */
    val upscale = listOf(
        Entry("Upscale_L", "Upscale x2 L", 2),
        Entry("Upscale_VL", "Upscale x2 VL", 2),
        Entry("Upscale_UL", "Upscale x2 UL", 2),
        Entry("Upscale_Denoise_L", "Upscale + Denoise x2 L", 2),
        Entry("Upscale_Denoise_VL", "Upscale + Denoise x2 VL", 2),
        Entry("Upscale_Denoise_UL", "Upscale + Denoise x2 UL", 2),
        Entry("Anime4K_Upscale_GAN_x2_S", "GAN x2 S", 2),
        Entry("Anime4K_Upscale_GAN_x2_M", "GAN x2 M", 2),
        Entry("Anime4K_Upscale_GAN_x3_L", "GAN x3 L", 3),
        Entry("Anime4K_Upscale_GAN_x3_VL", "GAN x3 VL", 3),
        Entry("Anime4K_Upscale_GAN_x4_UL", "GAN x4 UL", 4),
        Entry("Anime4K_Upscale_GAN_x4_UUL", "GAN x4 UUL (heaviest)", 4),
    )

    fun scaleOf(name: String): Int = (restore + upscale).firstOrNull { it.name == name }?.scale ?: 1

    fun file(context: Context, name: String): File =
        File(File(context.filesDir, "colorizer/anime4k"), "$name.onnx")

    fun isInstalled(context: Context, name: String): Boolean = file(context, name).length() > 0

    /**
     * Downloads one model from [baseUrl] (a folder link; the file is `<name>.onnx`) and keeps it only if
     * it opens as a usable model. Throws [IOException] with a readable message on failure.
     */
    suspend fun download(context: Context, name: String, baseUrl: String, onProgress: (Float) -> Unit = {}) {
        withIOContext {
            val target = file(context, name)
            val temp = File(target.parentFile, "$name.part")
            try {
                temp.parentFile?.mkdirs()
                val connection = URL(baseUrl.trim().trimEnd('/') + "/$name.onnx").openConnection() as HttpURLConnection
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
                            val buffer = ByteArray(64 * 1024)
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
                // Opening the model checks its input and output, and finds its scale.
                try {
                    Anime4kStageUpscaler.validate(temp)
                } catch (e: Exception) {
                    throw IOException("This is not a usable Anime4K model: ${e.message}", e)
                }
                if (!temp.renameTo(target)) temp.copyTo(target, overwrite = true)
            } finally {
                temp.delete()
            }
        }
    }
}
