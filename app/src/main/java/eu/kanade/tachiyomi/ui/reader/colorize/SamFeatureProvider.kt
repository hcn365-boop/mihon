package eu.kanade.tachiyomi.ui.reader.colorize

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SamFeatures(val level0: FloatArray, val level1: FloatArray)

object SamFeatureProvider {

    private const val LEVEL0_SIZE = 256 * 64 * 64
    private const val LEVEL1_SIZE = 256 * 32 * 32

    /** قراءة ملف الميزات الجاهز sam_features.bin مباشرة من المجلد */
    fun load(dir: File): SamFeatures? {
        val file = File(dir, "sam_features.bin")
        if (!file.isFile || file.length() != ((LEVEL0_SIZE + LEVEL1_SIZE) * 4).toLong()) return null
        
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return SamFeatures(
            FloatArray(LEVEL0_SIZE).also { buffer.get(it) },
            FloatArray(LEVEL1_SIZE).also { buffer.get(it) }
        )
    }
}
