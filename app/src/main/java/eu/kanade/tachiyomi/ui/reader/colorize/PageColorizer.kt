package eu.kanade.tachiyomi.ui.reader.colorize

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * Colors a manga page for the reader.
 *
 * It never throws: if anything goes wrong (no model, huge image, already colored, out of memory...)
 * the original page is returned, so reading is never broken by this feature.
 */
object PageColorizer {

    private const val MODEL_DIR = "colorizer"
    private const val MODEL_FILE_NAME = "v6_generator.onnx"
    private const val CACHE_DIR = "colorizer_cache"

    // Chroma is predicted at 512x512 per channel.
    private const val CHROMA_SIZE = 512 * 512
    private const val MAX_PIXELS = 12_000_000L
    private const val MAX_CACHE_BYTES = 256L * 1024 * 1024
    private const val JPEG_QUALITY = 95

    private val mutex = Mutex()
    private var engine: MangaColorizer? = null
    private var engineModified = 0L

    fun modelFile(context: Context): File = File(File(context.filesDir, MODEL_DIR), MODEL_FILE_NAME)

    fun isModelInstalled(context: Context): Boolean = modelFile(context).let { it.isFile && it.length() > 0 }

    /**
     * @param intensity 0 = no color, 1 = model color, up to 1.2
     * @return the colored page, or [source] itself when it should stay as it is
     */
    suspend fun colorize(context: Context, source: BufferedSource, intensity: Float): BufferedSource {
        return try {
            colorizeOrNull(context.applicationContext, source, intensity) ?: source
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            source
        }
    }

    private suspend fun colorizeOrNull(context: Context, source: BufferedSource, intensity: Float): BufferedSource? {
        val model = modelFile(context)
        if (intensity <= 0f || !model.isFile || model.length() == 0L) return null
        if (ImageUtil.isAnimatedAndSupported(source)) return null

        val bytes = source.peek().readByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) return null

        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        var colored: Bitmap? = null
        try {
            if (!looksGrayscale(original)) return null

            // One page at a time: keeps peak memory low and protects the single ONNX session.
            val result = mutex.withLock {
                val cache = cacheFile(context, bytes, model)
                val chroma = loadCached(cache) ?: engine(model).predict(original).also {
                    runCatching { saveCached(cache, it) }
                }
                engine(model).render(original, chroma, intensity)
            }
            colored = result

            val format = if (original.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val output = ByteArrayOutputStream()
            result.compress(format, JPEG_QUALITY, output)
            return Buffer().write(output.toByteArray())
        } finally {
            colored?.recycle()
            original.recycle()
        }
    }

    /** Reloads the model session if the model file was replaced. */
    private fun engine(model: File): MangaColorizer {
        val modified = model.lastModified()
        val current = engine
        if (current != null && modified == engineModified) return current
        engine?.close()
        engine = null
        return MangaColorizer(model).also {
            engine = it
            engineModified = modified
        }
    }

    /** Samples the page. Colored covers and already-colored pages are left alone. */
    private fun looksGrayscale(bitmap: Bitmap): Boolean {
        val side = 64
        val sample = Bitmap.createScaledBitmap(bitmap, side, side, true)
        try {
            val pixels = IntArray(side * side)
            sample.getPixels(pixels, 0, side, 0, 0, side, side)
            var coloredPixels = 0
            for (pixel in pixels) {
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (maxOf(r, g, b) - minOf(r, g, b) > 24) coloredPixels++
            }
            return coloredPixels < pixels.size / 20
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    }

    // region Chroma cache (a and b stored as signed bytes, about 0.5 MB per page)

    private fun cacheFile(context: Context, imageBytes: ByteArray, model: File): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(imageBytes)
        val name = digest.joinToString("") { "%02x".format(it) } + "_" + model.length()
        return File(File(context.cacheDir, CACHE_DIR), name)
    }

    private fun loadCached(file: File): MangaColorizer.Chroma? {
        if (!file.isFile) return null
        val data = file.readBytes()
        if (data.size != CHROMA_SIZE * 2) return null
        file.setLastModified(System.currentTimeMillis())
        return MangaColorizer.Chroma(
            FloatArray(CHROMA_SIZE) { data[it].toFloat() },
            FloatArray(CHROMA_SIZE) { data[CHROMA_SIZE + it].toFloat() },
        )
    }

    private fun saveCached(file: File, chroma: MangaColorizer.Chroma) {
        val dir = file.parentFile ?: return
        dir.mkdirs()
        val data = ByteArray(CHROMA_SIZE * 2)
        for (i in 0 until CHROMA_SIZE) {
            data[i] = chroma.a[i].coerceIn(-127f, 127f).roundToInt().toByte()
            data[CHROMA_SIZE + i] = chroma.b[i].coerceIn(-127f, 127f).roundToInt().toByte()
        }
        val temp = File(dir, file.name + ".tmp")
        temp.writeBytes(data)
        temp.renameTo(file)
        trimCache(dir)
    }

    /** Deletes the least recently used entries once the cache grows past the limit. */
    private fun trimCache(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_CACHE_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    // endregion
}
