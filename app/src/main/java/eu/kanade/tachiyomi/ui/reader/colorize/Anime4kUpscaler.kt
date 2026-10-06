package eu.kanade.tachiyomi.ui.reader.colorize

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer

/**
 * Anime4K "AI" upscaler: runs an ACNet ONNX model (1 channel in, 1 channel out, 2x) on the luminance
 * of the page and enlarges the color channels with plain interpolation.
 *
 * The page is processed in overlapping tiles, so memory stays small whatever the page size.
 * The result is opaque (alpha is not preserved). Not thread safe: use it from one coroutine at a time.
 */
class Anime4kUpscaler(modelFile: File) : Closeable {

    private class Spec(val inputName: String, val tileWidth: Int, val tileHeight: Int, val scale: Int)

    private class TileResult(val shape: LongArray, val values: FloatArray)

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val spec: Spec

    /** How many times each side is enlarged (2 for ACNet). */
    val scale: Int
        get() = spec.scale

    init {
        check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }
        session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
        spec = try {
            inspect(session)
        } catch (e: Throwable) {
            session.close()
            throw e
        }
    }

    fun upscale(input: Bitmap): Bitmap = Mats().use { m ->
        val rgba = m.mat()
        toMat(input, rgba)
        val rgb = m.mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val ycrcb = m.mat()
        Imgproc.cvtColor(rgb, ycrcb, Imgproc.COLOR_RGB2YCrCb)

        val luma = m.mat()
        Core.extractChannel(ycrcb, luma, 0)
        luma.convertTo(luma, CvType.CV_32F, 1.0 / 255.0)
        val lumaUp = upscaleLuma(luma, m)
        // Converting to 8-bit saturates, so values outside 0..1 are clamped.
        val lumaUp8 = m.mat()
        lumaUp.convertTo(lumaUp8, CvType.CV_8U, 255.0)

        val size = lumaUp8.size()
        val cr = m.mat()
        val cb = m.mat()
        Core.extractChannel(ycrcb, cr, 1)
        Core.extractChannel(ycrcb, cb, 2)
        Imgproc.resize(cr, cr, size, 0.0, 0.0, Imgproc.INTER_LINEAR)
        Imgproc.resize(cb, cb, size, 0.0, 0.0, Imgproc.INTER_LINEAR)
        Core.merge(listOf(lumaUp8, cr, cb), ycrcb)
        Imgproc.cvtColor(ycrcb, rgb, Imgproc.COLOR_YCrCb2RGB)

        val out = m.mat()
        Imgproc.cvtColor(rgb, out, Imgproc.COLOR_RGB2RGBA)
        val bitmap = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out, bitmap)
        bitmap
    }

    override fun close() {
        session.close()
    }

    /** Enlarges a single-channel float image (values 0..1) tile by tile. */
    private fun upscaleLuma(source: Mat, m: Mats): Mat {
        val scale = spec.scale
        val tileWidth = spec.tileWidth
        val tileHeight = spec.tileHeight
        val coreWidth = tileWidth - 2 * PAD
        val coreHeight = tileHeight - 2 * PAD
        val output = m.own(Mat(source.rows() * scale, source.cols() * scale, CvType.CV_32F))

        var y = 0
        while (y < source.rows()) {
            val h = minOf(coreHeight, source.rows() - y)
            var x = 0
            while (x < source.cols()) {
                val w = minOf(coreWidth, source.cols() - x)
                Mats().use { t ->
                    // Take the tile plus a margin of real pixels, and repeat the edge where the page ends.
                    val x0 = maxOf(0, x - PAD)
                    val y0 = maxOf(0, y - PAD)
                    val x1 = minOf(source.cols(), x + w + PAD)
                    val y1 = minOf(source.rows(), y + h + PAD)
                    val left = maxOf(0, PAD - x)
                    val top = maxOf(0, PAD - y)
                    val region = t.own(Mat(source, Rect(x0, y0, x1 - x0, y1 - y0)))
                    val tile = t.mat()
                    Core.copyMakeBorder(
                        region,
                        tile,
                        top,
                        tileHeight - top - region.rows(),
                        left,
                        tileWidth - left - region.cols(),
                        Core.BORDER_REPLICATE,
                    )
                    val data = FloatArray(tileHeight * tileWidth)
                    tile.get(0, 0, data)

                    val result = runTile(session, spec.inputName, data, tileHeight, tileWidth)
                    val predicted = t.mat(tileHeight * scale, tileWidth * scale, CvType.CV_32F)
                    predicted.put(0, 0, result.values)

                    // The margin only gives context to the model; keep just the core of the tile.
                    val crop = t.own(Mat(predicted, Rect(PAD * scale, PAD * scale, w * scale, h * scale)))
                    val destination = t.own(Mat(output, Rect(x * scale, y * scale, w * scale, h * scale)))
                    crop.copyTo(destination)
                }
                x += w
            }
            y += h
        }
        return output
    }

    /** Checks the model contract and finds its tile size and scale by running one blank tile. */
    private fun inspect(session: OrtSession): Spec {
        check(session.numInputs == 1L && session.numOutputs == 1L) {
            "Expected a model with one image input and one image output"
        }
        val name = session.inputNames.first()
        val shape = (session.inputInfo.getValue(name).info as? TensorInfo)?.shape
            ?: error("The model input is not a tensor")
        check(shape.size == 4 && shape[1] == 1L) { "Expected a 1-channel NCHW image input (ACNet)" }
        val height = if (shape[2] > 0) shape[2].toInt() else DEFAULT_TILE
        val width = if (shape[3] > 0) shape[3].toInt() else DEFAULT_TILE
        check(width > 2 * PAD && height > 2 * PAD) { "The model input is too small to tile" }

        val probe = runTile(session, name, FloatArray(height * width), height, width).shape
        check(
            probe.size == 4 && probe[0] == 1L && probe[1] == 1L &&
                probe[2] % height == 0L && probe[3] % width == 0L && probe[2] / height == probe[3] / width,
        ) { "Unsupported model output shape: ${probe.joinToString()}" }
        val factor = (probe[3] / width).toInt()
        check(factor in 1..4) { "Unsupported scale: $factor" }
        return Spec(name, width, height, factor)
    }

    private fun runTile(session: OrtSession, name: String, data: FloatArray, height: Int, width: Int): TileResult {
        val shape = longArrayOf(1, 1, height.toLong(), width.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
            session.run(mapOf(name to input)).use { result ->
                val output = result[0] as OnnxTensor
                val buffer = output.floatBuffer
                val values = FloatArray(buffer.remaining())
                buffer.get(values)
                return TileResult(output.info.shape, values)
            }
        }
    }

    private fun toMat(bitmap: Bitmap, destination: Mat) {
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        try {
            Utils.bitmapToMat(argb, destination)
        } finally {
            if (argb !== bitmap) argb.recycle()
        }
    }

    /** Releases every Mat created through it, even if processing throws. */
    private class Mats : Closeable {
        private val owned = ArrayList<Mat>()

        fun mat(): Mat = Mat().also { owned.add(it) }

        fun mat(rows: Int, cols: Int, type: Int): Mat = Mat(rows, cols, type).also { owned.add(it) }

        fun own(mat: Mat): Mat = mat.also { owned.add(it) }

        override fun close() {
            owned.forEach { it.release() }
        }
    }

    private companion object {
        // Margin of real pixels around each tile, so tile borders do not show.
        const val PAD = 16
        const val DEFAULT_TILE = 160
    }
}
