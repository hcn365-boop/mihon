package eu.kanade.tachiyomi.ui.reader.colorize

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** The two guide tensors the colorizer generator takes. */
class SamFeatures(val level0: FloatArray, val level1: FloatArray)

/**
 * Provides SAM encoder features. Modified to support extracting features
 * dynamically from actual page bitmaps for experimentation purposes.
 */
object SamFeatureProvider {

    private const val LEVEL0_SIZE = 256 * 64 * 64
    private const val LEVEL1_SIZE = 256 * 32 * 32

    /** Used for an input size the model leaves open (dynamic). */
    private const val DEFAULT_SIDE = 512L

    /** Original method: computes features once from a blank (all-zero) image and caches them. */
    fun load(model: File, acceleration: Acceleration, threads: Int): SamFeatures? {
        if (model.length() == 0L) return null
        val cache = File(model.parentFile, "sam_features_${model.length()}.bin")
        readCache(cache)?.let { return it }
        val features = try {
            compute(model, acceleration, threads)
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            null
        } ?: return null
        runCatching { writeCache(cache, features) }
        return features
    }

    /**
     * New method: extracts SAM features dynamically from an actual page [Bitmap].
     * Runs the encoder on every page as requested for experimentation.
     */
    fun extract(model: File, bitmap: Bitmap, acceleration: Acceleration, threads: Int): SamFeatures? {
        if (model.length() == 0L) return null
        return try {
            computeWithBitmap(model, bitmap, acceleration, threads)
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    private fun compute(model: File, acceleration: Acceleration, threads: Int): SamFeatures? {
        val env = OrtEnvironment.getEnvironment()
        OrtSessions.create(env, model.absolutePath, acceleration, threads).use { session ->
            val name = session.inputNames.first()
            val info = session.inputInfo.getValue(name).info as? TensorInfo
            if (info == null || info.type != OnnxJavaType.FLOAT) {
                logcat(LogPriority.ERROR) { "SAM encoder: unexpected input $name" }
                return null
            }
            val shape = LongArray(info.shape.size) { i ->
                when {
                    info.shape[i] > 0 -> info.shape[i]
                    i == 0 -> 1L
                    else -> DEFAULT_SIDE
                }
            }
            val count = shape.fold(1L) { total, size -> total * size }.toInt()
            OnnxTensor.createTensor(env, FloatBuffer.allocate(count), shape).use { input ->
                session.run(mapOf(name to input)).use { result ->
                    var level0: FloatArray? = null
                    var level1: FloatArray? = null
                    val seen = ArrayList<String>()
                    for (i in 0 until result.size()) {
                        val tensor = result.get(i) as? OnnxTensor ?: continue
                        val s = tensor.info.shape
                        seen.add(s.joinToString("x"))
                        if (s.size == 4 && s[1] == 256L && s[2] == 64L && s[3] == 64L) level0 = read(tensor, LEVEL0_SIZE)
                        if (s.size == 4 && s[1] == 256L && s[2] == 32L && s[3] == 32L) level1 = read(tensor, LEVEL1_SIZE)
                    }
                    if (level0 == null || level1 == null) {
                        logcat(LogPriority.ERROR) {
                            "SAM encoder: input ${shape.joinToString("x")}, outputs ${seen.joinToString()} (need 1x256x64x64 and 1x256x32x32)"
                        }
                        return null
                    }
                    return SamFeatures(level0, level1)
                }
            }
        }
    }

    private fun computeWithBitmap(model: File, bitmap: Bitmap, acceleration: Acceleration, threads: Int): SamFeatures? {
        val env = OrtEnvironment.getEnvironment()
        OrtSessions.create(env, model.absolutePath, acceleration, threads).use { session ->
            val name = session.inputNames.first()
            val info = session.inputInfo.getValue(name).info as? TensorInfo
            if (info == null || info.type != OnnxJavaType.FLOAT) {
                logcat(LogPriority.ERROR) { "SAM encoder: unexpected input $name" }
                return null
            }
            val shape = LongArray(info.shape.size) { i ->
                when {
                    info.shape[i] > 0 -> info.shape[i]
                    i == 0 -> 1L
                    else -> DEFAULT_SIDE
                }
            }

            val height = if (shape.size >= 4) shape[2].toInt() else 1024
            val width = if (shape.size >= 4) shape[3].toInt() else 1024

            val floatData = bitmapToFloatArray(bitmap, width, height)

            OnnxTensor.createTensor(env, FloatBuffer.wrap(floatData), shape).use { input ->
                session.run(mapOf(name to input)).use { result ->
                    var level0: FloatArray? = null
                    var level1: FloatArray? = null
                    val seen = ArrayList<String>()
                    for (i in 0 until result.size()) {
                        val tensor = result.get(i) as? OnnxTensor ?: continue
                        val s = tensor.info.shape
                        seen.add(s.joinToString("x"))
                        if (s.size == 4 && s[1] == 256L && s[2] == 64L && s[3] == 64L) level0 = read(tensor, LEVEL0_SIZE)
                        if (s.size == 4 && s[1] == 256L && s[2] == 32L && s[3] == 32L) level1 = read(tensor, LEVEL1_SIZE)
                    }
                    if (level0 == null || level1 == null) {
                        logcat(LogPriority.ERROR) {
                            "SAM encoder: input ${shape.joinToString("x")}, outputs ${seen.joinToString()} (need 1x256x64x64 and 1x256x32x32)"
                        }
                        return null
                    }
                    return SamFeatures(level0, level1)
                }
            }
        }
    }

    private fun bitmapToFloatArray(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        val pixels = IntArray(targetWidth * targetHeight)
        scaled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        if (scaled !== bitmap) scaled.recycle()

        val channels = 3
        val planeSize = targetWidth * targetHeight
        val result = FloatArray(channels * planeSize)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            result[i] = r
            result[planeSize + i] = g
            result[2 * planeSize + i] = b
        }
        return result
    }

    private fun read(tensor: OnnxTensor, size: Int): FloatArray {
        val buffer = tensor.floatBuffer
        return FloatArray(size).also { buffer.get(it) }
    }

    private fun readCache(file: File): SamFeatures? {
        if (!file.isFile || file.length() != ((LEVEL0_SIZE + LEVEL1_SIZE) * 4).toLong()) return null
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return SamFeatures(FloatArray(LEVEL0_SIZE).also { buffer.get(it) }, FloatArray(LEVEL1_SIZE).also { buffer.get(it) })
    }

    private fun writeCache(file: File, features: SamFeatures) {
        val bytes = ByteBuffer.allocate((LEVEL0_SIZE + LEVEL1_SIZE) * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(features.level0).put(features.level1)
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(bytes.array())
        temp.renameTo(file)
    }
}
