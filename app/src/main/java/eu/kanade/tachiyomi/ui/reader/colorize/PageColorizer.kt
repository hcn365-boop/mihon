package eu.kanade.tachiyomi.ui.reader.colorize

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.TensorInfo
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
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
    private val upscalerManager = EngineManager<PageUpscaler>(maxEntries = 1)
    private val acnetManager = EngineManager<Anime4kUpscaler>(maxEntries = 1)

    fun modelFile(context: Context): File = File(File(context.filesDir, MODEL_DIR), COLORIZER_FILE)

    fun samModelFile(context: Context): File = File(File(context.filesDir, MODEL_DIR), "v6_sam_encoder.onnx")

    fun upscalerModelFile(context: Context): File = File(File(context.filesDir, MODEL_DIR), UPSCALER_FILE)

    private fun samActive(context: Context): Boolean = AiConfig.colorizeUseSam && samModelFile(context).length() > 0

    fun isModelInstalled(context: Context): Boolean = modelFile(context).length() > 0

    private fun acnetReady(context: Context): Boolean = true // أصبح النموذج ثابتاً ومدمجاً في الـ Assets

    /**
     * دالة مؤقتة لتوليد وحفظ ملف ميزات الـ SAM لمرة واحدة على صفحة بيضاء فارغة في ذاكرة الجهاز
     * بالاسم المحدد: sam_features.bin
     */
    fun generateAndSaveSamFeaturesOnce(context: Context) {
        val modelFile = samModelFile(context)
        if (!modelFile.exists()) {
            logcat(LogPriority.ERROR) { "SamGen: نموذج الـ SAM غير موجود!" }
            return
        }

        val outputFile = File(File(context.filesDir, MODEL_DIR), "sam_features.bin")
        if (outputFile.exists()) {
            logcat(LogPriority.INFO) { "SamGen: ملف الميزات موجود مسبقاً في: ${outputFile.absolutePath}" }
            return
        }

        try {
            val env = OrtEnvironment.getEnvironment()
            OrtSessions.create(env, modelFile.absolutePath, Acceleration.CPU, 1).use { session ->
                val inputName = session.inputNames.first()
                val info = session.inputInfo.getValue(inputName).info as? TensorInfo
                
                val defaultSide = 512L
                val shape = LongArray(info?.shape?.size ?: 4) { i ->
                    val dim = info?.shape?.get(i) ?: -1
                    if (dim > 0) dim else defaultSide
                }

                val width = shape[3].toInt()
                val height = shape[2].toInt()
                val whiteBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.WHITE)
                }

                val count = shape.fold(1L) { acc, s -> acc * s }.toInt()
                val floatBuffer = FloatBuffer.allocate(count)
                val intValues = IntArray(width * height)
                whiteBitmap.getPixels(intValues, 0, width, 0, 0, width, height)

                for (i in intValues.indices) {
                    val pixel = intValues[i]
                    val r = ((pixel shr 16) and 0xFF) / 255.0f
                    val g = ((pixel shr 8) and 0xFF) / 255.0f
                    val b = (pixel and 0xFF) / 255.0f
                    
                    floatBuffer.put(i, r)
                    floatBuffer.put(width * height + i, g)
                    floatBuffer.put(2 * width * height + i, b)
                }
                floatBuffer.rewind()

                OnnxTensor.createTensor(env, floatBuffer, shape).use { inputTensor ->
                    session.run(mapOf(inputName to inputTensor)).use { result ->
                        var level0: FloatArray? = null
                        var level1: FloatArray? = null

                        val level0Size = 256 * 64 * 64
                        val level1Size = 256 * 32 * 32

                        for (i in 0 until result.size()) {
                            val tensor = result[i] as? OnnxTensor ?: continue
                            val s = tensor.info.shape
                            val buf = tensor.floatBuffer
                            
                            if (s.size == 4 && s[1] == 256L && s[2] == 64L && s[3] == 64L) {
                                level0 = FloatArray(level0Size).also { buf.get(it) }
                            }
                            if (s.size == 4 && s[1] == 256L && s[2] == 32L && s[3] == 32L) {
                                level1 = FloatArray(level1Size).also { buf.get(it) }
                            }
                        }

                        if (level0 != null && level1 != null) {
                            val bytes = ByteBuffer.allocate((level0Size + level1Size) * 4).order(ByteOrder.LITTLE_ENDIAN)
                            bytes.asFloatBuffer().put(level0).put(level1)
                            
                            outputFile.writeBytes(bytes.array())
                            logcat(LogPriority.INFO) { "SamGen: تم حفظ ملف الميزات بنجاح في: ${outputFile.absolutePath}" }
                        } else {
                            logcat(LogPriority.ERROR) { "SamGen: فشل استخراج المخرجات المطلوبة من نموذج الـ SAM" }
                        }
                    }
                }
                whiteBitmap.recycle()
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "SamGen: حدث خطأ أثناء توليد ملف الميزات" }
        }
    }

    /**
     * تفريغ جميع المحركات وإغلاق جلساتها يدوياً عند الخروج أو تغيير الفصول لتنظيف الذاكرة بالكامل.
     * لا تستدعِها أثناء معالجة صفحة: الداخلية الوحيدة الآمنة هي عبر [mutex] (انظر baseImage).
     */
    fun releaseEngines() {
        colorizerManager.reset()
        upscalerManager.reset()
        acnetManager.reset()
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
        val intensity = AiConfig.colorizeIntensity
        val wantColor = AiConfig.colorizeEnabled && intensity > 0f && colorModel.length() > 0
        val wantUpscale = AiConfig.upscaleEnabled && upscaleReady(context)
        val wantFinal = AiConfig.upscaleFinalPassEnabled && acnetReady(context)
        val translation = if (translate) AiConfig.translation else null
        val wantShrink = AiConfig.normalizeSide > 0
        if (!wantColor && !wantUpscale && !wantShrink && !wantFinal && translation == null) return null
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
        if (!doColor && !doUpscale && !doShrink && !wantFinal && translationConfig == null) return null

        val imageHash = hash(bytes)
        val shrinkSide = if (doShrink) normalize else 0
        val baseName = baseName(context, imageHash, if (doColor) intensity else -1f, doUpscale, shrinkSide, colorModel, samActive(context))
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

        val base = if (doColor || doUpscale || doShrink || wantFinal) {
            baseImage(context, bytes, imageHash, baseName, doColor, doUpscale, shrinkSide, intensity, pixels, wantFinal)
        } else {
            null
        }
        if (translationConfig == null) return base?.let { Buffer().write(it) }

        if (!display) {
            var ocrBitmap: Bitmap? = null
            try {
                val s = translationConfig
                val targetBytes = base ?: bytes
                PageTranslationService.enqueue(context, effective!!, s.ocrMode, imageHash, s.limits) {
                    decode(targetBytes).also { ocrBitmap = it }
                }
            } finally {
                ocrBitmap?.recycle()
            }
            return base?.let { Buffer().write(it) }
        }

        return translateAndDraw(context, translationConfig, effective!!, imageHash, base ?: bytes, finalName!!)
            ?: base?.let { Buffer().write(it) }
    }

    /** A page bigger than the allowed output size is scaled down: the viewer needs a full buffer per page. */
    private fun capSize(bitmap: Bitmap): Bitmap = PageResizer.fitTo(bitmap, AiConfig.upscaleMaxOutputPixels)

    /** The last step: the built-in ACNet model refines the page. Not run when the page or memory is too big. */
    private fun acnetFinalPass(context: Context, page: Bitmap): Bitmap {
        val pixels = page.width.toLong() * page.height
        if (pixels > AiConfig.upscaleFinalPassMaxPixels) return page
        if (pixels * (14 + 4 * 21) > AiConfig.memoryBudgetBytes) return page
        val acceleration = if (acceleratorFailed) Acceleration.CPU else AiConfig.upscaleAcceleration
        val key = "${acceleration}_${AiConfig.aiThreads}"
        
        val engine = acnetManager.getOrPut(key) { Anime4kUpscaler(context, acceleration, AiConfig.aiThreads) }
        
        val enlarged = engine.upscale(page)
        if (!AiConfig.upscaleFinalPassKeepSize) return capSize(enlarged)
        val restored = PageResizer.resizeTo(enlarged, page.width, page.height)
        if (restored !== enlarged) enlarged.recycle()
        return restored
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
        wantFinal: Boolean,
    ): ByteArray? {
        val file = cacheFile(context, baseName)
        if (file.isFile && file.length() > 0L) {
            file.setLastModified(System.currentTimeMillis())
            return file.readBytes()
        }

        val original = decode(bytes) ?: return null
        val working = if (shrinkSide > 0) PageResizer.shrink(original, shrinkSide) else original
        var result: Bitmap? = null
        try {
            var color = doColor
            val upscale = doUpscale && !original.hasAlpha()
            val finalPass = wantFinal && acnetReady(context) && !original.hasAlpha()
            if (color && !looksGrayscale(working)) color = false
            if (!color && !upscale && !finalPass && working === original) return null
            if (estimateBytes(pixels, color, upscale, upscaleScale()) > AiConfig.memoryBudgetBytes) return null

            val processed = try {
                mutex.withLock {
                    var page = working
                    if (color) {
                        val colorModel = modelFile(context)
                        val chromaFile = chromaFile(context, imageHash, colorModel)
                        val engine = colorizerEngine(context, colorModel)
                        val chroma = loadChroma(chromaFile) ?: engine.predict(working).also {
                            runCatching { saveChroma(context, chromaFile, it) }
                        }
                        page = engine.render(working, chroma, intensity)
                    }
                    if (upscale) {
                        val enlarged = (upscalerEngine(context) ?: error("No upscaler model"))
                            .process(page, AiConfig.upscaleMaxOutputPixels, PageResizer.MAX_SIDE)
                        if (page !== working) page.recycle()
                        page = enlarged
                    }
                    if (finalPass) {
                        val refined = acnetFinalPass(context, page)
                        if (refined !== page && page !== working) page.recycle()
                        page = refined
                    }
                    
                    // تطبيق فلتر التشبع الآمن لرفع حيوية الألوان
                    val saturated = applySaturation(page, 1.3f)
                    if (saturated !== page && page !== working) page.recycle()
                    saturated
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val accelerated = (color && AiConfig.colorizeAcceleration != Acceleration.CPU) ||
                    (upscale && AiConfig.upscaleAcceleration != Acceleration.CPU)
                if (accelerated && !acceleratorFailed) {
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

    private fun applySaturation(bitmap: Bitmap, saturation: Float): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply { setSaturation(saturation) }
            )
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return output
    }

    private suspend fun translateAndDraw(
        context: Context,
        config: TranslationConfig,
        settings: TranslatorSettings,
        imageHash: String,
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
            ) { decode(baseBytes).also { ocrBitmap = it } }
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
        if (estimateBytes(pixels, true, false, 1) > AiConfig.memoryBudgetBytes) return
        val chromaFile = chromaFile(context, hash(bytes), model)
        if (chromaFile.isFile) return
        val original = decode(bytes) ?: return
        try {
            if (!looksGrayscale(original)) return
            mutex.withLock {
                if (!chromaFile.isFile) saveChroma(context, chromaFile, colorizerEngine(context, model).predict(original))
            }
        } finally {
            original.recycle()
        }
    }

    private fun estimateBytes(pixels: Long, color: Boolean, upscale: Boolean, scale: Int): Long {
        var total = pixels * 4
        if (color) total += pixels * 20
        if (upscale) total += pixels * 14 + pixels * scale * scale * 21
        return total
    }

    private fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    private fun encode(bitmap: Bitmap, hasAlpha: Boolean): ByteArray {
        val format = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val output = ByteArrayOutputStream()
        bitmap.compress(format, JPEG_QUALITY, output)
        return output.toByteArray()
    }

    @Volatile
    private var acceleratorFailed = false

    private fun colorizerEngine(context: Context, model: File): MangaColorizer {
        val acceleration = if (acceleratorFailed) Acceleration.CPU else AiConfig.colorizeAcceleration
        val sam = samActive(context)
        val setup = "$acceleration/${AiConfig.aiThreads}/sam=$sam"
        val key = "${model.absolutePath}_${setup}_${model.lastModified()}"
        return colorizerManager.getOrPut(key) {
            val guide = if (sam) {
                try {
                    SamFeatureProvider.load(samModelFile(context), Acceleration.CPU, AiConfig.aiThreads)
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "SAM encoder: failed to load model file" }
                    null
                }
            } else {
                null
            }

            MangaColorizer(model, acceleration, AiConfig.aiThreads, guide)
        }
    }

    private fun chosenModelFiles(context: Context): List<File> =
        listOf(AiConfig.upscaleRestoreModel, AiConfig.upscaleMainModel)
            .filter { it.isNotEmpty() }
            .map { Anime4kModels.file(context, it) }

    private fun upscaleReady(context: Context): Boolean =
        chosenModelFiles(context).let { files -> files.isNotEmpty() && files.all { it.length() > 0 } }

    private fun upscaleScale(): Int =
        Anime4kModels.scaleOf(AiConfig.upscaleRestoreModel) * Anime4kModels.scaleOf(AiConfig.upscaleMainModel)

    private fun upscaleSignature(): String = "m_${AiConfig.upscaleRestoreModel}_${AiConfig.upscaleMainModel}"

    private fun upscalerEngine(context: Context): PageUpscaler? {
        val files = chosenModelFiles(context)
        if (files.isEmpty() || files.any { it.length() == 0L }) return null
        val acceleration = if (acceleratorFailed) Acceleration.CPU else AiConfig.colorizeAcceleration
        val key = "models_${files.joinToString("|") { "${it.name}_${it.lastModified()}" }}_${acceleration}_${AiConfig.aiThreads}"
        return upscalerManager.getOrPut(key) { Anime4kStageUpscaler(files, acceleration, AiConfig.aiThreads) }
    }

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

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun baseName(
        context: Context,
        imageHash: String,
        intensity: Float,
        upscale: Boolean,
        shrinkSide: Int,
        colorModel: File,
        sam: Boolean,
    ): String {
        val color = if (intensity < 0f) "n" else (intensity * 100).roundToInt().toString()
        val upscaleStamp = if (upscale) "${upscaleSignature()}_${AiConfig.upscaleMaxOutputPixels / 1_000_000}" else "n"
        val finalPassStamp = "_f${if (AiConfig.upscaleFinalPassEnabled) "1" else "0"}${if (AiConfig.upscaleFinalPassKeepSize) "k" else "g"}${AiConfig.upscaleFinalPassMaxPixels / 1_000_000}"
        return "${imageHash}_c${color}${if (sam) "s" else ""}_${colorModel.length()}_u${upscaleStamp}_s$shrinkSide$finalPassStamp"
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
        val temp = File(dir, "${file.name}.${System.nanoTime()}.tmp")
        temp.writeBytes(data)
        temp.renameTo(file)
        trimCache(dir, AiConfig.cacheBytes)
    }

    private fun chromaFile(context: Context, imageHash: String, model: File): File =
        File(File(context.cacheDir, CHROMA_CACHE_DIR), imageHash + "_" + model.length() + (if (samActive(context)) "_sam" else ""))

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

    private fun saveChroma(context: Context, file: File, chroma: MangaColorizer.Chroma) {
        val dir = file.parentFile ?: return
        dir.mkdirs()
        val data = ByteArray(CHROMA_SIZE * 2)
        for (i in 0 until CHROMA_SIZE) {
            data[i] = chroma.a[i].coerceIn(-127f, 127f).roundToInt().toByte()
            data[CHROMA_SIZE + i] = chroma.b[i].coerceIn(-127f, 127f).roundToInt().toByte()
        }
        val temp = File(dir, "${file.name}.${System.nanoTime()}.tmp")
        temp.writeBytes(data)
        temp.renameTo(file)
        trimCache(dir, MAX_CHROMA_CACHE_BYTES)
    }

    private fun trimCache(dir: File, limit: Long) {
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
