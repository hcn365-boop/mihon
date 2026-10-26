package eu.kanade.tachiyomi.ui.reader.colorize

import ai.onnxruntime.OnnxJavaType
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import org.opencv.core.Size
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Universal Anime4K & Restoration upscaler: supports 1-channel, 3-channel, 
 * and Float16 (FP16) ONNX models dynamically.
 */
class Anime4kUpscaler(modelFile: File, acceleration: Acceleration = Acceleration.CPU, threads: Int = 0) : Closeable {

    private class Spec(
        val inputName: String,
        val tileWidth: Int,
        val tileHeight: Int,
        val scale: Int,
        val channels: Int,
        val inputType: OnnxJavaType,
    )

    private class TileResult(val shape: LongArray, val values: FloatArray)

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val spec: Spec

    val scale: Int
        get() = spec.scale

    init {
        check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }
        session = OrtSessions.create(env, modelFile.absolutePath, acceleration, threads)
        spec = try {
            inspect(session)
        } catch (e: Throwable) {
            session.close()
            throw e
        }
    }

    fun upscale(input: Bitmap, maxShortSide: Int = 0): Bitmap = Mats().use { m ->
        val rgba = m.mat()
        toMat(input, rgba)
        val rgb = m.mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)

        if (maxShortSide > 0) {
            val shortSide = min(rgb.cols(), rgb.rows())
            if (shortSide > maxShortSide) {
                val ratio = maxShortSide.toDouble() / shortSide
                val newSize = Size(
                    max(1, (rgb.cols() * ratio).roundToInt()).toDouble(),
                    max(1, (rgb.rows() * ratio).roundToInt()).toDouble(),
                )
                Imgproc.resize(rgb, rgb, newSize, 0.0, 0.0, Imgproc.INTER_AREA)
            }
        }

        if (spec.channels == 3) {
            val rgbFloat = m.mat()
            rgb.convertTo(rgbFloat, CvType.CV_32F, 1.0 / 255.0)
            val rgbUp = upscaleRgb(rgbFloat, m)
            val rgb8 = m.mat()
            rgbUp.convertTo(rgb8, CvType.CV_8U, 255.0)

            val out = m.mat()
            Imgproc.cvtColor(rgb8, out, Imgproc.COLOR_RGB2RGBA)
            val bitmap = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(out, bitmap)
            bitmap
        } else {
            val ycrcb = m.mat()
            Imgproc.cvtColor(rgb, ycrcb, Imgproc.COLOR_RGB2YCrCb)
            val luma = m.mat()
            Core.extractChannel(ycrcb, luma, 0)
            luma.convertTo(luma, CvType.CV_32F, 1.0 / 255.0)
            val lumaUp = upscaleLuma(luma, m)
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
    }

    override fun close() {
        session.close()
    }

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

                    val result = runTile(session, spec.inputName, data, tileHeight, tileWidth, 1, spec.inputType)
                    val predicted = t.mat(tileHeight * scale, tileWidth * scale, CvType.CV_32F)
                    predicted.put(0, 0, result.values)

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

    private fun upscaleRgb(source: Mat, m: Mats): Mat {
        val scale = spec.scale
        val tileWidth = spec.tileWidth
        val tileHeight = spec.tileHeight
        val coreWidth = tileWidth - 2 * PAD
        val coreHeight = tileHeight - 2 * PAD
        val planeSize = tileWidth * tileHeight
        val output = m.own(Mat(source.rows() * scale, source.cols() * scale, CvType.CV_32FC3))

        val rMat = m.mat()
        val gMat = m.mat()
        val bMat = m.mat()
        Core.extractChannel(source, rMat, 0)
        Core.extractChannel(source, gMat, 1)
        Core.extractChannel(source, bMat, 2)

        val outR = m.own(Mat(source.rows() * scale, source.cols() * scale, CvType.CV_32F))
        val outG = m.own(Mat(source.rows() * scale, source.cols() * scale, CvType.CV_32F))
        val outB = m.own(Mat(source.rows() * scale, source.cols() * scale, CvType.CV_32F))

        var y = 0
        while (y < source.rows()) {
            val h = minOf(coreHeight, source.rows() - y)
            var x = 0
            while (x < source.cols()) {
                val w = minOf(coreWidth, source.cols() - x)
                Mats().use { t ->
                    val x0 = maxOf(0, x - PAD)
                    val y0 = maxOf(0, y - PAD)
                    val x1 = minOf(source.cols(), x + w + PAD)
                    val y1 = minOf(source.rows(), y + h + PAD)
                    val left = maxOf(0, PAD - x)
                    val top = maxOf(0, PAD - y)

                    fun makeBorderChannel(srcMat: Mat): Mat {
                        val region = t.own(Mat(srcMat, Rect(x0, y0, x1 - x0, y1 - y0)))
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
                        return tile
                    }

                    val tileR = makeBorderChannel(rMat)
                    val tileG = makeBorderChannel(gMat)
                    val tileB = makeBorderChannel(bMat)

                    val rData = FloatArray(planeSize)
                    val gData = FloatArray(planeSize)
                    val bData = FloatArray(planeSize)
                    tileR.get(0, 0, rData)
                    tileG.get(0, 0, gData)
                    tileB.get(0, 0, bData)

                    val data = FloatArray(3 * planeSize)
                    System.arraycopy(rData, 0, data, 0, planeSize)
                    System.arraycopy(gData, 0, data, planeSize, planeSize)
                    System.arraycopy(bData, 0, data, 2 * planeSize, planeSize)

                    val result = runTile(session, spec.inputName, data, tileHeight, tileWidth, 3, spec.inputType)

                    val outPlaneSize = (tileHeight * scale) * (tileWidth * scale)
                    val resR = FloatArray(outPlaneSize)
                    val resG = FloatArray(outPlaneSize)
                    val resB = FloatArray(outPlaneSize)
                    System.arraycopy(result.values, 0, resR, 0, outPlaneSize)
                    System.arraycopy(result.values, outPlaneSize, resG, 0, outPlaneSize)
                    System.arraycopy(result.values, 2 * outPlaneSize, resB, 0, outPlaneSize)

                    val predR = t.mat(tileHeight * scale, tileWidth * scale, CvType.CV_32F).also { it.put(0, 0, resR) }
                    val predG = t.mat(tileHeight * scale, tileWidth * scale, CvType.CV_32F).also { it.put(0, 0, resG) }
                    val predB = t.mat(tileHeight * scale, tileWidth * scale, CvType.CV_32F).also { it.put(0, 0, resB) }

                    val cropR = t.own(Mat(predR, Rect(PAD * scale, PAD * scale, w * scale, h * scale)))
                    val cropG = t.own(Mat(predG, Rect(PAD * scale, PAD * scale, w * scale, h * scale)))
                    val cropB = t.own(Mat(predB, Rect(PAD * scale, PAD * scale, w * scale, h * scale)))

                    val destR = t.own(Mat(outR, Rect(x * scale, y * scale, w * scale, h * scale)))
                    val destG = t.own(Mat(outG, Rect(x * scale, y * scale, w * scale, h * scale)))
                    val destB = t.own(Mat(outB, Rect(x * scale, y * scale, w * scale, h * scale)))

                    cropR.copyTo(destR)
                    cropG.copyTo(destG)
                    cropB.copyTo(destB)
                }
                x += w
            }
            y += h
        }

        Core.merge(listOf(outR, outG, outB), output)
        return output
    }

    private fun inspect(session: OrtSession): Spec {
        check(session.numInputs == 1L && session.numOutputs == 1L) {
            "Expected a model with one image input and one image output"
        }
        val name = session.inputNames.first()
        val tensorInfo = session.inputInfo.getValue(name).info as? TensorInfo
            ?: error("The model input is not a tensor")
        val shape = tensorInfo.shape
        check(shape.size == 4 && (shape[1] == 1L || shape[1] == 3L)) {
            "Expected a 1-channel or 3-channel NCHW image input"
        }
        val channels = shape[1].toInt()
        val height = if (shape[2] > 0) shape[2].toInt() else DEFAULT_TILE
        val width = if (shape[3] > 0) shape[3].toInt() else DEFAULT_TILE
        check(width > 2 * PAD && height > 2 * PAD) { "The model input is too small to tile" }

        val probe = runTile(session, name, FloatArray(channels * height * width), height, width, channels, tensorInfo.type).shape
        check(
            probe.size == 4 && probe[0] == 1L && probe[1] == channels.toLong() &&
                probe[2] % height == 0L && probe[3] % width == 0L && probe[2] / height == probe[3] / width,
        ) { "Unsupported model output shape: ${probe.joinToString()}" }
        val factor = (probe[3] / width).toInt()
        check(factor in 1..4) { "Unsupported scale: $factor" }
        return Spec(name, width, height, factor, channels, tensorInfo.type)
    }

    private fun runTile(session: OrtSession, name: String, data: FloatArray, height: Int, width: Int, channels: Int, inputType: OnnxJavaType): TileResult {
        val shape = longArrayOf(1, channels.toLong(), height.toLong(), width.toLong())
        val tensor = if (inputType == OnnxJavaType.FLOAT16) {
            val byteBuffer = ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder())
            val shortBuffer = byteBuffer.asShortBuffer()
            for (f in data) {
                shortBuffer.put(floatToHalf(f))
            }
            OnnxTensor.createTensor(env, byteBuffer, shape, OnnxJavaType.FLOAT16)
        } else {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
        }
        tensor.use { input ->
            session.run(mapOf(name to input)).use { result ->
                val output = result[0] as OnnxTensor
                val buffer = output.floatBuffer
                val values = FloatArray(buffer.remaining())
                buffer.get(values)
                return TileResult(output.info.shape, values)
            }
        }
    }

    private fun floatToHalf(f: Float): Short {
        val bits = java.lang.Float.floatToIntBits(f)
        val s = (bits ushr 16) and 0x8000
        val exp = ((bits ushr 23) and 0xFF) - 112
        val mant = bits and 0x007FFFFF
        return when {
            exp <= 0 -> 0.toShort()
            exp >= 31 -> (s or 0x7C00).toShort()
            else -> (s or (exp shl 10) or (mant ushr 13)).toShort()
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
        const val PAD = 16
        const val DEFAULT_TILE = 160
    }
}
