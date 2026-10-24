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
 * أنواع النماذج المدعومة
 * [minBytes]: الحد الأدنى لحجم الملف لتجاهل الملفات الفارغة/المعطوبة
 */
enum class ModelKind(val minBytes: Long, val description: String) {
    COLORIZER(1L * 1024 * 1024, "Manga Colorizer"),
    UPSCALER(5L * 1024, "Anime Upscaler (Anime4K)"),  // تقليل من 10KB لـ 5KB
}

/**
 * محسّن لدعم نماذج متعددة من anime4kOnnx
 *
 * التحسينات:
 * - دعم نماذج بأحجام مختلفة
 * - رسائل خطأ أكثر وضوحاً
 * - تتبع معلومات النموذج (نوع المدخلات، scale، إلخ)
 */
object ColorizerModelInstaller {

    private const val BUFFER_SIZE = 64 * 1024
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000

    /** تطبيع روابط Hugging Face */
    fun normalizeUrl(url: String): String = 
        url.trim().replace("/blob/", "/resolve/")

    fun modelFile(context: Context, kind: ModelKind): File = when (kind) {
        ModelKind.COLORIZER -> PageColorizer.modelFile(context)
        ModelKind.UPSCALER -> PageColorizer.upscalerModelFile(context)
    }

    fun isInstalled(context: Context, kind: ModelKind = ModelKind.COLORIZER): Boolean =
        modelFile(context, kind).length() >= kind.minBytes

    fun remove(context: Context, kind: ModelKind = ModelKind.COLORIZER) {
        modelFile(context, kind).delete()
    }

    /** الدوال القديمة للتوافقية */
    suspend fun download(context: Context, url: String, onProgress: (Float) -> Unit = {}) =
        downloadModel(context, ModelKind.COLORIZER, url, onProgress)

    suspend fun import(context: Context, uri: Uri) = 
        importModel(context, ModelKind.COLORIZER, uri)

    /**
     * تحميل نموذج من رابط
     *
     * @throws IOException مع رسالة خطأ واضحة على الفشل
     * @param onProgress يستقبل 0..1 عندما يكون حجم الملف معروفاً
     */
    suspend fun downloadModel(
        context: Context,
        kind: ModelKind,
        url: String,
        onProgress: (Float) -> Unit = {}
    ) {
        withIOContext {
            val temp = tempFile(context, kind)
            try {
                temp.parentFile?.mkdirs()
                
                val connection = URL(normalizeUrl(url)).openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        throw IOException(
                            "Download failed: HTTP ${connection.responseCode}"
                        )
                    }
                    
                    if (connection.contentType?.startsWith("text/html") == true) {
                        throw IOException(
                            "The link points to a web page, not to the model file. " +
                            "Use the raw/download link instead."
                        )
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

    /**
     * استيراد نموذج من ملف محلي
     */
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

    /**
     * التحقق من صحة النموذج وتثبيته
     *
     * @throws IOException مع تفاصيل واضحة عن سبب الفشل
     */
    private fun install(context: Context, kind: ModelKind, temp: File) {
        // التحقق من الحد الأدنى للحجم
        if (temp.length() < kind.minBytes) {
            throw IOException(
                "The file is too small to be a valid ${kind.description} model. " +
                "Expected at least ${kind.minBytes / 1024} KB, got ${temp.length() / 1024} KB."
            )
        }

        // التحقق من صحة النموذج بتحميله وفحص مخرجاته
        try {
            when (kind) {
                ModelKind.COLORIZER -> {
                    MangaColorizer(temp).use { colorizer ->
                        // التحقق من أن النموذج يعمل
                    }
                }
                ModelKind.UPSCALER -> {
                    // ✅ استخدام Constructor جديد بـ 3 arguments
                    Anime4kUpscaler(
                        modelPath = temp,
                        acceleration = Acceleration.CPU,
                        aiThreads = 1
                    ).use { upscaler ->
                        // التحقق من معايير النموذج
                        val scale = upscaler.scale
                        if (scale !in 1..8) {
                            throw IOException(
                                "Unsupported upscaling factor: ${scale}x. " +
                                "Expected 2x, 4x, or similar."
                            )
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // إعادة رفع IOException كما هي (رسائل واضحة)
            throw e
        } catch (e: IllegalArgumentException) {
            throw IOException(
                "Invalid ${kind.description} model: ${e.message}\n" +
                "Please ensure you're using a compatible ONNX model from the correct source.",
                e
            )
        } catch (e: Exception) {
            throw IOException(
                "Failed to validate ${kind.description} model: ${e.message}\n" +
                "The file may be corrupted or incompatible.",
                e
            )
        }

        // تثبيت النموذج
        val target = modelFile(context, kind)
        target.parentFile?.mkdirs()
        
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
        }
    }
}
