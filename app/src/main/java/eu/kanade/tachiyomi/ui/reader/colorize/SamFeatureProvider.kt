package eu.kanade.tachiyomi.ui.reader.colorize

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/** تينسورز الميزات الخاصة بالـ SAM (level0 و level1) */
class SamFeatures(val level0: FloatArray, val level1: FloatArray)

object SamFeatureProvider {

    private const val LEVEL0_SIZE = 256 * 64 * 64
    private const val LEVEL1_SIZE = 256 * 32 * 32

    /** 
     * تحميل ميزات الـ SAM: يتم التحقق من وجود الملف محلياً، 
     * وإن لمש يكن موجوداً يتم نسخه تلقائياً من الـ Assets.
     */
    fun load(context: Context): SamFeatures? {
        val dir = File(context.filesDir, "colorizer")
        val file = File(dir, "sam_features.bin")
        
        // إذا لم يكن الملف موجوداً في الذاكرة الداخلية، قم بنسخه تلقائياً من الـ Assets
        if (!file.exists()) {
            try {
                dir.mkdirs()
                context.assets.open("manga-light-colorizer/sam_features.bin").use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to copy sam_features.bin from assets" }
                return null
            }
        }

        // التحقق من صحة وحجم الملف الثنائي
        if (!file.isFile || file.length() != ((LEVEL0_SIZE + LEVEL1_SIZE) * 4).toLong()) return null
        
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return SamFeatures(
            FloatArray(LEVEL0_SIZE).also { buffer.get(it) },
            FloatArray(LEVEL1_SIZE).also { buffer.get(it) }
        )
    }
}
