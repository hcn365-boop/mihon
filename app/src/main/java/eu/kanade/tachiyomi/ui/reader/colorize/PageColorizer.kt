package eu.kanade.tachiyomi.ui.reader.colorize

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.translation.PageTranslationRenderer
import eu.kanade.translation.PageTranslationService
import eu.kanade.translation.TranslationStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.math.sqrt
import eu.kanade.translation.translator.TranslatorSettings
import eu.kanade.translation.MangaTranslationContext

/**
 * Processes a manga page for the reader: colorize, then upscale, then translate (text drawn into the
 * image). Settings come from [AiConfig].
 *
 * It never throws: if anything goes wrong (no model, huge image, already colored, out of memory...)
 * the original page is returned, so reading is never broken by this feature.
 *
 * Two images are cached: the "base" (colorized and upscaled, no text) and the "final" (base with the
 * translation drawn). Changing only the translation or the font reuses the base, and the translated
 * text itself is kept in [TranslationStore], so nothing is read or translated twice.
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

    // One lock per page name (shared between a few names), so the same page is never built twice at once
    private val pageLocks = Array(16) { Mutex() }

    private fun pageLock(name: String): Mutex = pageLocks[(name.hashCode() and Int.MAX_VALUE) % pageLocks.size]

    /**
     * مدير جلسات محركات الذكاء الاصطناعي (مستوحى من تصميم ModelManager لمنع تسريب Native Memory وتجنب SIGSEGV).
     * يحافظ على حد أقصى من الجلسات النشطة ويغلق القديمة تلقائياً لتفريغ الذاكرة الأصلية.
     */
    private class EngineManager<T : Closeable>(private val maxEntries: Int = 2) {
        private val sessions = object : LinkedHashMap<String, T>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>): Boolean {
                if (size > maxEntries) {
                    try {
                        eldest.value.close()
                    } catch (_: Throwable) {}
                    return true
                }
                return false
            }
        }

        fun getOrPut(key: String, factory: () -> T): T {
            synchronized(sessions) {
                sessions[key]?.let { return it }
                val instance = factory()
                sessions[key] = instance
                while (sessions.size > maxEntries) {
                    val iterator = sessions.entries.iterator()
                    if (iterator.hasNext()) {
                        val entry = iterator.next()
                        try {
                            entry.value.close()
                        } catch (_: Throwable) {}
                        iterator.remove()
                    }
                }
                return instance
            }
        }

        fun reset() {
            synchronized(sessions) {
                sessions.values.forEach {
                    try { it.close() } catch (_: Throwable) {}
                }
                sessions.clear()
            }
        }
    }

    private val colorizerManager = EngineManager<MangaColorizer>(maxEntries = 1)
    private val upscalerManager = EngineManager<Anime4kUpscaler>(maxEntries = 1)

    fun modelFile(context: Context): File = File(File(context.filesDir, MODEL_DIR), COLORIZER_FILE)

    fun upscalerModelFile(context: Context): File = File(File(context.filesDir, MODEL_DIR), UPSCALER_FILE)

    fun isModelInstalled(context: Context): Boolean = modelFile(context).length() > 0

    fun isUpscalerInstalled(context: Context): Boolean = upscalerModelFile(context).length() > 0

    /**
     * تفريغ جميع المحركات وإغلاق جلساتها يدوياً عند الخروج أو تغيير الفصول لتنظيف الذاكرة بالكامل.
     * لا تستدعِها أثناء معالجة صفحة: الداخلية الوحيدة الآمنة هي عبر [mutex] (انظر baseImage).
     */
    fun releaseEngines() {
        colorizerManager.reset()
        upscalerManager.reset()
    }

    /** Kept for the existing callers: the intensity now comes from [AiConfig]. */
    suspend fun colorize(context: Context, source: BufferedSource, intensity: Float): BufferedSource =
        process(context, source)

    /** For the page on screen. @return the processed page, or [source] itself when it should stay as it is */
    suspend fun process(context: Context, source: BufferedSource): BufferedSource {
        return try {
            run(context.applicationContext, source, display = true) ?: source
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            source
        }
    }

    /**
     * Background work for a page that is not on screen yet. Never throws.
     * [full]: build the base image (and, if [translate], read the text and queue it for translation).
     * Not [full]: only warm the colorizer result, which is cheap to reuse later.
     */
    suspend fun prefetch(context: Context, source: BufferedSource, full: Boolean, translate: Boolean = false) {
        try {
            val app = context.applicationContext
            if (full) run(app, source, display = false, translate = translate) else warmChroma(app, source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }

    private suspend fun run(
        context: Context,
        source: BufferedSource,
        display: Boolean,
        translate: Boolean = true,
    ): BufferedSource? {
        val colorModel = modelFile(context)
        val upscaleModel = upscalerModelFile(context)
        val intensity = AiConfig.colorizeIntensity
        val wantColor = AiConfig.colorizeEnabled && intensity > 0f && colorModel.length() > 0
        val wantUpscale = AiConfig.upscaleEnabled && upscaleModel.length() > 0
        val translation = if (translate) AiConfig.translation else null
        val wantShrink = AiConfig.normalizeSide > 0
        if (!wantColor && !wantUpscale && !wantShrink && translation == null) return null
        if (ImageUtil.isAnimatedAndSupported(source)) return null

        val bytes = source.peek().readByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val pixels = bounds.outWidth.toLong() * bounds.outHeight

        val doColor = wantColor && pixels <= AiConfig.colorizeMaxPixels
        val shortSide = minOf(bounds.outWidth, bounds.outHeight)
        val normalize = AiConfig.normalizeSide
        val upscaleInputPixels = if (normalize in 1 until shortSide) {
            val ratio = normalize.toDouble() / shortSide
            (pixels * ratio * ratio).toLong()
        } else {
            pixels
        }
        val doUpscale = wantUpscale && upscaleInputPixels <= AiConfig.upscaleMaxPixels
        val doShrink = normalize in 1 until shortSide
        val translationConfig = translation?.takeIf { pixels <= AiConfig.colorizeMaxPixels }
        if (!doColor && !doUpscale && !doShrink && translationConfig == null) return null

        val imageHash = hash(bytes)
        val shrinkSide = if (doShrink) normalize else 0
        val baseName = baseName(imageHash, if (doColor) intensity else -1f, doUpscale, shrinkSide, colorModel, upscaleModel)
        val effective = translationConfig?.effectiveSettings()
        val finalName = translationConfig?.let { config ->
            val s = effective!!
            val key = TranslationStore.key(
                s.from.name, s.to.name, s.engine.name, s.model, config.ocrMode.name, config.font.name,
                config.filter.signature, AiConfig.forceTranslate(MangaTranslationContext.mangaId).toString(),
            )
            "${baseName}_T$key"
        }

        // A page finished before is read straight from disk.
        if (display && finalName != null) readCachedPage(cacheFile(context, finalName))?.let { return it }

        val base = if (doColor || doUpscale || doShrink) {
            baseImage(context, bytes, imageHash, baseName, doColor, doUpscale, shrinkSide, intensity, pixels)
        } else {
            null
        }
        if (translationConfig == null) return base?.let { Buffer().write(it) }

        if (!display) {
            // Read the text now and queue it for translation, so the page is quick to finish later.
            var ocrBitmap: Bitmap? = null
            try {
                val s = translationConfig
                PageTranslationService.enqueue(context, effective!!, s.ocrMode, imageHash, s.limits) {
                    decode(bytes).also { ocrBitmap = it }
                }
            } finally {
                ocrBitmap?.recycle()
            }
            return base?.let { Buffer().write(it) }
        }

        return translateAndDraw(context, translationConfig, effective!!, imageHash, bytes, base ?: bytes, finalName!!)
            ?: base?.let { Buffer().write(it) }
    }

    private suspend fun baseImage(
        context: Context,
        bytes: ByteArray,
        imageHash: String,
        baseName: String,
        doColor: Boolean,
        doUpscale: Boolean,
        shrinkSide: Int,
        intensity: Float,
        pixels: Long,
    ): ByteArray? {
        val file = cacheFile(context, baseName)
        if (file.isFile && file.length() > 0L) {
            file.setLastModified(System.currentTimeMillis())
            return file.readBytes()
        }

        val original = decode(bytes) ?: return null
        // Many sites enlarge small scans: shrinking them back first removes the empty pixels.
        val working = if (shrinkSide > 0) PageResizer.shrink(original, shrinkSide) else original
        var result: Bitmap? = null
        try {
            var color = doColor
            // The upscaler output is opaque, so pages with transparency are not upscaled.
            val upscale = doUpscale && !original.hasAlpha()
            if (color && !looksGrayscale(working)) color = false
            if (!color && !upscale && working === original) return null
            if (estimateBytes(pixels, color, upscale) > AiConfig.memoryBudgetBytes) return null

            // One page at a time: keeps peak memory low and protects the ONNX sessions.
            val processed = try {
                mutex.withLock {
                    var page = working
                    if (color) {
                        val colorModel = modelFile(context)
                        val chromaFile = chromaFile(context, imageHash, colorModel)
                        val engine = colorizerEngine(colorModel)
                        val chroma = loadChroma(chromaFile) ?: engine.predict(working).also {
                            runCatching { saveChroma(chromaFile, it) }
                        }
                        page = engine.render(working, chroma, intensity)
                    }
                    if (upscale) {
                        var enlarged = upscalerEngine(upscalerModelFile(context)).upscale(page)
                        if (page !== working) page.recycle()
                        // A result that is too big is scaled down: the viewer needs a full buffer per page.
                        val outputPixels = enlarged.width.toLong() * enlarged.height
                        if (outputPixels > AiConfig.upscaleMaxOutputPixels) {
                            val ratio = sqrt(AiConfig.upscaleMaxOutputPixels.toDouble() / outputPixels)
                            val smaller = Bitmap.createScaledBitmap(
                                enlarged,
                                (enlarged.width * ratio).roundToInt().coerceAtLeast(1),
                                (enlarged.height * ratio).roundToInt().coerceAtLeast(1),
                                true,
                            )
                            if (smaller !== enlarged) enlarged.recycle()
                            enlarged = smaller
                        }
                        page = enlarged
                    }
                    page
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val accelerated = (color && AiConfig.colorizeAcceleration != Acceleration.CPU) ||
                    (upscale && AiConfig.upscaleAcceleration != Acceleration.CPU)
                if (accelerated && !acceleratorFailed) {
                    // The accelerator failed: use the CPU from now on (until the app is restarted).
                    acceleratorFailed = true
                    mutex.withLock { releaseEngines() }
                }
                throw e
            }
            result = processed

            val encoded = encode(processed, original.hasAlpha())
            runCatching { saveCachedPage(file, encoded) }
            return encoded
        } finally {
            result?.recycle()
            if (working !== original) working.recycle()
            original.recycle()
        }
    }

    /**
     * Gets the page's translation (from the store, or by reading and translating it) and draws it
     * on the base image. Returns null when the page has no text or the translation failed,
     * so the caller shows the base image instead.
     */
    private suspend fun translateAndDraw(
        context: Context,
        config: TranslationConfig,
        settings: TranslatorSettings,
        imageHash: String,
        originalBytes: ByteArray,
        baseBytes: ByteArray,
        finalName: String,
    ): BufferedSource? {
        var ocrBitmap: Bitmap? = null
        val page = try {
            PageTranslationService.translateNow(
                context,
                settings,
                config.ocrMode,
                imageHash,
                config.limits,
            ) { decode(originalBytes).also { ocrBitmap = it } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            null
        } finally {
            ocrBitmap?.recycle()
        } ?: return null

        val base = decode(baseBytes) ?: return null
        var drawn: Bitmap? = null
        try {
            val result = PageTranslationRenderer.render(base, page, config.font.typeface(context))
            if (result !== base) drawn = result
            val encoded = encode(result, base.hasAlpha())
            runCatching { saveCachedPage(cacheFile(context, finalName), encoded) }
            return Buffer().write(encoded)
        } finally {
            drawn?.recycle()
            base.recycle()
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
        val chromaFile = chromaFile(context, hash(bytes), model)
        if (chromaFile.isFile) return
        val original = decode(bytes) ?: return
        try {
            if (!looksGrayscale(original)) return
            mutex.withLock {
                if (!chromaFile.isFile) saveChroma(chromaFile, colorizerEngine(model).predict(original))
            }
        } finally {
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

    private fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    private fun encode(bitmap: Bitmap, hasAlpha: Boolean): ByteArray {
        val format = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val output = ByteArrayOutputStream()
        bitmap.compress(format, JPEG_QUALITY, output)
        return output.toByteArray()
    }

    // region Engines (managed securely via EngineManager)

    @Volatile
    private var acceleratorFailed = false

    private fun colorizerEngine(model: File): MangaColorizer {
        val acceleration = if (acceleratorFailed) Acceleration.CPU else AiConfig.colorizeAcceleration
        val setup = "$acceleration/${AiConfig.aiThreads}"
        val key = "${model.absolutePath}_${setup}_${model.lastModified()}"
        return colorizerManager.getOrPut(key) {
            MangaColorizer(model, acceleration, AiConfig.aiThreads)
        }
    }

    private fun upscalerEngine(model: File): Anime4kUpscaler {
        val acceleration = if (acceleratorFailed) Acceleration.CPU else AiConfig.upscaleAcceleration
        val setup = "$acceleration/${AiConfig.aiThreads}"
        val key = "${model.absolutePath}_${setup}_${model.lastModified()}"
        return upscalerManager.getOrPut(key) {
            Anime4kUpscaler(model, acceleration, AiConfig.aiThreads)
        }
    }

    // endregion

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

    // region Processed page cache (base and final images, trimmed to AiConfig.cacheBytes)

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun baseName(
        imageHash: String,
        intensity: Float,
        upscale: Boolean,
        shrinkSide: Int,
        colorModel: File,
        upscaleModel: File,
    ): String {
        val color = if (intensity < 0f) "n" else (intensity * 100).roundToInt().toString()
        // upscaleMaxOutputPixels في اسم الملف حتى لا تُقرأ صور ضخمة قديمة من الكاش بعد تغيير السقف
        val upscaleStamp = if (upscale) "${upscaleModel.length()}_${AiConfig.upscaleMaxOutputPixels / 1_000_000}" else "n"
        return "${imageHash}_c${color}_${colorModel.length()}_u${upscaleStamp}_s$shrinkSide"
    }

    private fun cacheFile(context: Context, name: String): File = File(File(context.cacheDir, PAGE_CACHE_DIR), name)

    private fun readCachedPage(file: File): BufferedSource? {
        if (!file.isFile || file.length() == 0L) return null
        file.setLastModified(System.currentTimeMillis())
        return Buffer().write(file.readBytes())
    }

    private fun saveCachedPage(file: File, data: ByteArray) {
        val dir = file.parentFile ?: return
        dir.mkdirs()
        // اسم مؤقت فريد حتى لا يكتب مساران في الملف نفسه معاً ويتلفا الناتج
        val temp = File(dir, "${file.name}.${System.nanoTime()}.tmp")
        temp.writeBytes(data)
        temp.renameTo(file)
        trimCache(dir, AiConfig.cacheBytes)
    }

    // endregion

    // region Chroma cache (a and b stored as signed bytes, about 0.5 MB per page)

    private fun chromaFile(context: Context, imageHash: String, model: File): File =
        File(File(context.cacheDir, CHROMA_CACHE_DIR), imageHash + "_" + model.length())

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
        // اسم مؤقت فريد كما في saveCachedPage
        val temp = File(dir, "${file.name}.${System.nanoTime()}.tmp")
        temp.writeBytes(data)
        temp.renameTo(file)
        trimCache(dir, MAX_CHROMA_CACHE_BYTES)
    }

    // endregion

    /** Deletes the least recently used entries once the folder grows past [limit] bytes. */
    private fun trimCache(dir: File, limit: Long) {
        // تجاهل الملفات المؤقتة الناقلة حتى لا تُحذف أثناء الكتابة
        val files = dir.listFiles()
            ?.filter { !it.name.endsWith(".tmp") }
            ?.sortedBy { it.lastModified() }
            ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= limit) break
            total -= file.length()
            file.delete()
        }
    }
}
