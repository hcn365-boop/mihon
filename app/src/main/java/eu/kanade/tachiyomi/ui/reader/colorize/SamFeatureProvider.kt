package eu.kanade.tachiyomi.ui.reader.colorize

import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.TensorInfo
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class SamFeatures(val level0: FloatArray, val level1: FloatArray)

object SamFeatureProvider {

    private const val LEVEL0_SIZE = 256 * 64 * 64
    private const val LEVEL1_SIZE = 256 * 32 * 32
    private const val DEFAULT_SIDE = 512L

    fun load(modelDir: File, samModelFile: File, acceleration: Acceleration, threads: Int): SamFeatures? {
        val binFile = File(modelDir, "sam_features.bin")
        
        // 1. إذا كان ملف الـ bin الجاهز موجوداً، قم بتحميله فوراً دون استخدام الـ ONNX
        if (binFile.exists() && binFile.length() > 0L) {
            try {
                readCache(binFile)?.let { return it }
            } catch (_: Throwable) {}
        }

        // 2. إذا لم يكن موجوداً، ولكن نموذج الـ ONNX موجود، قم بتوليده تلقائياً وحفظه!
        if (!samModelFile.exists() || samModelFile.length() == 0L) return null

        return try {
            val features = compute(samModelFile, acceleration, threads)
            if (features != null) {
                writeCache(binFile, features)
            }
            features
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "SamFeatureProvider: failed to compute features" }
            null
        }
    }

    private fun compute(model: File, acceleration: Acceleration, threads: Int): SamFeatures? {
        val env = OrtEnvironment.getEnvironment()
        OrtSessions.create(env, model.absolutePath, acceleration, threads).use { session ->
            val name = session.inputNames.first()
            val info = session.inputInfo.getValue(name).info as? TensorInfo
            val shape = LongArray(info?.shape?.size ?: 4) { i ->
                val dim = info?.shape?.get(i) ?: -1
                if (dim > 0) dim else DEFAULT_SIDE
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

            OnnxTensor.createTensor(env, floatBuffer, shape).use { input ->
                session.run(mapOf(name to input)).use { result ->
                    var level0: FloatArray? = null
                    var level1: FloatArray? = null
                    for (i in 0 until result.size()) {
                        val tensor = result[i] as? OnnxTensor ?: continue
                        val s = tensor.info.shape
                        val buf = tensor.floatBuffer
                        if (s.size == 4 && s[1] == 256L && s[2] == 64L && s[3] == 64L) {
                            level0 = FloatArray(LEVEL0_SIZE).also { buf.get(it) }
                        }
                        if (s.size == 4 && s[1] == 256L && s[2] == 32L && s[3] == 32L) {
                            level1 = FloatArray(LEVEL1_SIZE).also { buf.get(it) }
                        }
                    }
                    whiteBitmap.recycle()
                    if (level0 == null || level1 == null) return null
                    return SamFeatures(level0, level1)
                }
            }
        }
    }

    private fun readCache(file: File): SamFeatures? {
        if (!file.isFile || file.length() != ((LEVEL0_SIZE + LEVEL1_SIZE) * 4).toLong()) return null
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return SamFeatures(
            FloatArray(LEVEL0_SIZE).also { buffer.get(it) },
            FloatArray(LEVEL1_SIZE).also { buffer.get(it) }
        )
    }

    private fun writeCache(file: File, features: SamFeatures) {
        val bytes = ByteBuffer.allocate((LEVEL0_SIZE + LEVEL1_SIZE) * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(features.level0).put(features.level1)
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(bytes.array())
        temp.renameTo(file)
    }
}
