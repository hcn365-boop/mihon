package eu.kanade.tachiyomi.ui.reader.colorize

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object PageResizer {

    /**
     * Shrinks the page so its short side is [maxShortSide] pixels, keeping the proportions.
     * The short side (not the long one) tells the real resolution of any page: a wide double page
     * and a tall strip both keep their detail. Area averaging is used because it is the right filter
     * for making an image smaller.
     *
     * @return [bitmap] itself when it is not larger than the limit (not recycled, not copied),
     * otherwise a new smaller bitmap. The caller owns the new bitmap.
     */
    fun shrink(bitmap: Bitmap, maxShortSide: Int): Bitmap {
        val shortSide = min(bitmap.width, bitmap.height)
        if (maxShortSide <= 0 || shortSide <= maxShortSide) return bitmap
        check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }

        val ratio = maxShortSide.toDouble() / shortSide
        val width = max(1, (bitmap.width * ratio).roundToInt())
        val height = max(1, (bitmap.height * ratio).roundToInt())

        val source = Mat()
        val target = Mat()
        try {
            Utils.bitmapToMat(bitmap, source)
            Imgproc.resize(source, target, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(target, output)
            return output
        } finally {
            source.release()
            target.release()
        }
    }
}
