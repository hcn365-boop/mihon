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
 * Processes a manga page for the reader: colorize first, then upscale. Settings come from [AiConfig].
 *
 * It never throws: if anything goes wrong (no model, huge image, already colored, out of memory...)
 * the original page is returned, so reading is never broken by this feature.
 */
object PageColorizer {

    private const val MODEL_DIR = "colorizer"
    private const val COLORIZER_FILE = "v6_generator.onnx"
    private const val UPSCALER_FILE = "anime4k_acnet.onnx"
    private const val CHROMA_CACHE_DIR = "colorizer_cache"
    private const val PAGE_CACHE_DIR = "ai_pages"

    // Chroma is predicted at 512x512 per channel.
    private const val CHROMA_SIZE = 512 * 512
    private const val MAX_CHROMA_CACHE_BYTES = 256L * 1024 * 1024
    private const val JPEG_QUALITY = 95

    private val mutex = Mutex()
    private var colorizer: MangaColorizer? = null
    private var colorizerStamp = 0L
    private var upscaler: Anime4kUpscaler? = null
    private var upscalerStamp = 0L

    fun modelFile(context: Context): File = File(File(context.filesDir, MODEL_DIR), COLORIZER_FILE)

    fun upscalerModelFile(context: Context): File = File(File(context.filesDir, MODEL_DIR), UPSCALER_FILE)

    fun isModelInstalled(context: Context): Boolean = modelFile(context).length() > 0

    fun isUpscalerInstalled(context: Context): Boolean = upscalerModelFile(context).length() > 0

    /** Kept for the existing callers: the intensity now comes from [AiConfig]. */
    suspend fun colorize(context: Context, source: BufferedSource, intensity: Float): BufferedSource =
        process(context, source)

    /** @return the processed page, or [source] itself when it should stay as it is */
    suspend fun process(context: Context, source: BufferedSource): BufferedSource {
        return try {
            processOrNull(context.applicationContext, source) ?: source
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            source
        }
    }

    private suspend fun processOrNull(context: Context, source: BufferedSource): BufferedSource? {
        val colorModel = modelFile(context)
        val upscaleModel = upscalerModelFile(context)
        val intensity = AiConfig.colorizeIntensity
        val wantColor = AiConfig.colorizeEnabled && intensity > 0f && colorModel.length() > 0
        val wantUpscale = AiConfig.upscaleEnabled && upscaleModel.length() > 0
        if (!wantColor && !wantUpscale) return null
        if (ImageUtil.isAnimatedAndSupported(source)) return null

        val bytes = source.peek().readByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val pixels = bounds.outWidth.toLong() * bounds.outHeight

        var doColor = wantColor && pixels <= AiConfig.colorizeMaxPixels
        var doUpscale = wantUpscale && pixels <= AiConfig.upscaleMaxPixels
        if (!doColor && !doUpscale) return null

        // A page processed before is read straight from disk.
        val colorKey = if (doColor) intensity else -1f
        val pageCache = pageCacheFile(context, bytes, colorKey, doUpscale, colorModel, upscaleModel)
        readCachedPage(pageCache)?.let { return it }

        if (estimateBytes(pixels, doColor, doUpscale) > AiConfig.memoryBudgetBytes) return null

        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        var result: Bitmap? = null
        try {
            // The upscaler output is opaque, so pages with transparency are not upscaled.
            if (original.hasAlpha()) doUpscale = false
            if (doColor && !looksGrayscale(original)) doColor = false
            if (!doColor && !doUpscale) return null

            // One page at a time: keeps peak memory low and protects the ONNX sessions.
            val processed = mutex.withLock {
                var page = original
                if (doColor) {
                    val chromaFile = chromaFile(context, bytes, colorModel)
                    val engine = colorizerEngine(colorModel)
                    val chroma = loadChroma(chromaFile) ?: engine.predict(original).also {
                        runCatching { saveChroma(chromaFile, it) }
                    }
                    page = engine.render(original, chroma, intensity)
                }
                if (doUpscale) {
                    val enlarged = upscalerEngine(upscaleModel).upscale(page)
                    if (page !== original) page.recycle()
                    page = enlarged
                }
                page
            }
            result = processed

            val format = if (original.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val output = ByteArrayOutputStream()
            processed.compress(format, JPEG_QUALITY, output)
            val encoded = output.toByteArray()
            runCatching { saveCachedPage(pageCache, encoded) }
            return Buffer().write(encoded)
        } finally {
            result?.recycle()
            original.recycle()
        }
    }

    /**
     * Rough peak memory for one page, in bytes, from its pixel count. It is an estimate,
     * not a hard limit: the system may still run out of memory (the page is then left as it is).
     */
    private fun estimateBytes(pixels: Long, color: Boolean, upscale: Boolean): Long {
        var total = pixels * 4 // decoded page
        if (color) total += pixels * 20
        // ACNet doubles each side: 4x the pixels at about 21 bytes per output pixel.
        if (upscale) total += pixels * 14 + pixels * 4 * 21
        return total
    }

    // region Engines (reloaded if the model file is replaced)

    private fun colorizerEngine(model: File): MangaColorizer {
        val stamp = model.lastModified()
        val current = colorizer
        if (current != null && stamp == colorizerStamp) return current
        colorizer?.close()
        colorizer = null
        return MangaColorizer(model).also {
            colorizer = it
            colorizerStamp = stamp
        }
    }

    private fun upscalerEngine(model: File): Anime4kUpscaler {
        val stamp = model.lastModified()
        val current = upscaler
        if (current != null && stamp == upscalerStamp) return current
        upscaler?.close()
        upscaler = null
        return Anime4kUpscaler(model).also {
            upscaler = it
            upscalerStamp = stamp
        }
    }

    // endregion

        /** Background work for a page that is not on screen yet. Never throws. */
    suspend fun prefetch(context: Context, source: BufferedSource, full: Boolean) {
        try {
            val app = context.applicationContext
            if (full) processOrNull(app, source) else warmChroma(app, source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }

    /** Runs only the colorizer model and caches its chroma, so the page is quick to color later. */
    private suspend fun warmChroma(context: Context, source: BufferedSource) {
        val model = modelFile(context)
        if (!AiConfig.colorizeEnabled || model.length() == 0L) return
        if (ImageUtil.isAnimatedAndSupported(source)) return
        val bytes = source.peek().readByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        val pixels = bounds.outWidth.toLong() * bounds.outHeight
        if (pixels > AiConfig.colorizeMaxPixels) return
        if (estimateBytes(pixels, true, false) > AiConfig.memoryBudgetBytes) return
        val chromaFile = chromaFile(context, bytes, model)
        if (chromaFile.isFile) return
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        try {
            if (!looksGrayscale(original)) return
            mutex.withLock {
                if (!chromaFile.isFile) saveChroma(chromaFile, colorizerEngine(model).predict(original))
            }
        } finally {
            original.recycle()
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

    // region Processed page cache (final images, trimmed to AiConfig.cacheBytes)

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun pageCacheFile(
        context: Context,
        imageBytes: ByteArray,
        intensity: Float,
        upscale: Boolean,
        colorModel: File,
        upscaleModel: File,
    ): File {
        val color = if (intensity < 0f) "n" else (intensity * 100).roundToInt().toString()
        val upscaleStamp = if (upscale) upscaleModel.length().toString() else "n"
        val name = "${hash(imageBytes)}_c${color}_${colorModel.length()}_u$upscaleStamp"
        return File(File(context.cacheDir, PAGE_CACHE_DIR), name)
    }

    private fun readCachedPage(file: File): BufferedSource? {
        if (!file.isFile || file.length() == 0L) return null
        file.setLastModified(System.currentTimeMillis())
        return Buffer().write(file.readBytes())
    }

    private fun saveCachedPage(file: File, data: ByteArray) {
        val dir = file.parentFile ?: return
        dir.mkdirs()
        val temp = File(dir, file.name + ".tmp")
        temp.writeBytes(data)
        temp.renameTo(file)
        trimCache(dir, AiConfig.cacheBytes)
    }

    // endregion

    // region Chroma cache (a and b stored as signed bytes, about 0.5 MB per page)

    private fun chromaFile(context: Context, imageBytes: ByteArray, model: File): File =
        File(File(context.cacheDir, CHROMA_CACHE_DIR), hash(imageBytes) + "_" + model.length())

    private fun loadChroma(file: File): MangaColorizer.Chroma? {
        if (!file.isFile) return null
        val data = file.readBytes()
        if (data.size != CHROMA_SIZE * 2) return null
        file.setLastModified(System.currentTimeMillis())
        return MangaColorizer.Chroma(
            FloatArray(CHROMA_SIZE) { data[it].toFloat() },
            FloatArray(CHROMA_SIZE) { data[CHROMA_SIZE + it].toFloat() },
        )
    }

    private fun saveChroma(file: File, chroma: MangaColorizer.Chroma) {
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
        trimCache(dir, MAX_CHROMA_CACHE_BYTES)
    }

    // endregion

    /** Deletes the least recently used entries once the folder grows past [limit] bytes. */
    private fun trimCache(dir: File, limit: Long) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= limit) break
            total -= file.length()
            file.delete()
        }
    }
}
