package eu.kanade.tachiyomi.ui.reader.colorize

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.TensorInfo
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** The two guide tensors the colorizer generator takes (the same for every page). */
class SamFeatures(val level0: FloatArray, val level1: FloatArray)

/**
 * The official colorizer script runs the SAM 2.1 encoder on a blank (all zero) image and gives the
 * result to the generator as its guide. A blank image always gives the same result, so it is computed
 * once, saved in a small file, and the big encoder model is closed again: pages never run it.
 *
 * Only the shapes the generator expects are used: [1, 256, 64, 64] and [1, 256, 32, 32]. If the encoder
 * has other outputs or inputs, the shapes it has are logged and null is returned, so the colorizer
 * keeps using zero guides.
 */
object SamFeatureProvider {

    // تم تحديث الأحجام بناءً على مخرجات النموذج الفعليّة (64x64 و 32x32)
    private const val LEVEL0_SIZE = 256 * 64 * 64
    private const val LEVEL1_SIZE = 256 * 32 * 32

    /** Used for an input size the model leaves open (dynamic). */
    private const val DEFAULT_SIDE = 512L

    /** @return the features, or null when the model is missing or is not the expected encoder */
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
                        val tensor = result[i] as? OnnxTensor ?: continue
                        val s = tensor.info.shape
                        seen.add(s.joinToString("x"))
                        // البحث عن الأبعاد الجديدة المطابقة لمخرجات النموذج (64x64 و 32x32)
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
