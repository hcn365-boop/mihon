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
import kotlin.math.sqrt

/** Making pages smaller. Area averaging is used everywhere: it is the right filter for shrinking an image. */
internal object PageResizer {

    /** Longest side allowed for a processed page. JPEG cannot store more than 65,535 pixels on a side. */
    const val MAX_SIDE = 30_000

    /**
     * How much an image of this size must be scaled so that it has at most [maxPixels] pixels and its
     * longest side is at most [maxSide]. 1.0 means it already fits.
     */
    fun fitRatio(width: Int, height: Int, maxPixels: Long, maxSide: Int): Double {
        var ratio = 1.0
        val pixels = width.toLong() * height
        if (pixels > maxPixels) ratio = min(ratio, sqrt(maxPixels.toDouble() / pixels))
        val side = max(width, height)
        if (side * ratio > maxSide) ratio = maxSide.toDouble() / side
        return ratio
    }

    /**
     * Shrinks the page so its short side is [maxShortSide] pixels, keeping the proportions.
     * The short side (not the long one) tells the real resolution of any page: a wide double page
     * and a tall strip both keep their detail.
     *
     * @return [bitmap] itself when it is not larger than the limit, otherwise a new smaller bitmap
     */
    fun shrink(bitmap: Bitmap, maxShortSide: Int): Bitmap {
        val shortSide = min(bitmap.width, bitmap.height)
        if (maxShortSide <= 0 || shortSide <= maxShortSide) return bitmap
        val ratio = maxShortSide.toDouble() / shortSide
        return resizeTo(bitmap, (bitmap.width * ratio).roundToInt(), (bitmap.height * ratio).roundToInt())
    }

    /**
     * Scales the bitmap down so it fits [maxPixels] and [maxSide]. [bitmap] is recycled when a new one is made.
     * @return [bitmap] itself when it already fits
     */
    fun fitTo(bitmap: Bitmap, maxPixels: Long, maxSide: Int = MAX_SIDE): Bitmap {
        val ratio = fitRatio(bitmap.width, bitmap.height, maxPixels, maxSide)
        if (ratio >= 1.0) return bitmap
        val smaller = resizeTo(bitmap, (bitmap.width * ratio).roundToInt(), (bitmap.height * ratio).roundToInt())
        bitmap.recycle()
        return smaller
    }

    /** A new bitmap of exactly [width] x [height] pixels. [bitmap] is not changed. */
    fun resizeTo(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }
        val w = max(1, width)
        val h = max(1, height)
        val source = Mat()
        val target = Mat()
        try {
            Utils.bitmapToMat(bitmap, source)
            Imgproc.resize(source, target, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(target, output)
            return output
        } finally {
            source.release()
            target.release()
        }
    }
}
