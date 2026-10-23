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
import org.opencv.core.Size
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * محسّن لدعم نماذج anime4kOnnx المتعددة
 * يدعم: RGB و Grayscale inputs، scales مختلفة
 */
class Anime4kUpscaler(
    modelFile: File,
    acceleration: Acceleration = Acceleration.CPU,
    threads: Int = 0
) : Closeable {

    private class Spec(
        val inputName: String,
        val outputName: String,
        val tileWidth: Int,
        val tileHeight: Int,
        val scale: Int,
        val channels: Int,  // 1 for grayscale, 3 for RGB
        val isRgb: Boolean
    )

    private class TileResult(val shape: LongArray, val values: FloatArray)

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val spec: Spec

    val scale: Int
        get() = spec.scale

    init {
        check(OpenCVLoader.initLocal()) { 
            "OpenCV native library failed to load" 
        }
        session = OrtSessions.create(
            env,
            modelFile.absolutePath,
            acceleration,
            threads
        )
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

        // تحويل للصيغة المطلوبة
        val processed = if (spec.isRgb) {
            // للنماذج RGB: تحويل مباشر RGBA → RGB
            val rgb = m.mat()
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            rgb
        } else {
            // للنماذج Grayscale: تحويل RGBA → RGB → YCrCb ثم استخراج Y
            val rgb = m.mat()
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            val ycrcb = m.mat()
            Imgproc.cvtColor(rgb, ycrcb, Imgproc.COLOR_RGB2YCrCb)
            val luma = m.mat()
            Core.extractChannel(ycrcb, luma, 0)
            luma
        }

        // تصغير إذا لزم الأمر
        if (maxShortSide > 0) {
            val shortSide = min(processed.cols(), processed.rows())
            if (shortSide > maxShortSide) {
                val ratio = maxShortSide.toDouble() / shortSide
                val newSize = Size(
                    max(1, (processed.cols() * ratio).roundToInt()).toDouble(),
                    max(1, (processed.rows() * ratio).roundToInt()).toDouble(),
                )
                Imgproc.resize(
                    processed,
                    processed,
                    newSize,
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA
                )
            }
        }

        // تحويل للـ float
        val floatMat = m.mat()
        processed.convertTo(floatMat, CvType.CV_32F, 1.0 / 255.0)

        // Upscale
        val upscaled = if (spec.isRgb) {
            upscaleRgb(floatMat, m)
        } else {
            upscaleLuma(floatMat, m)
        }

        // تحويل للـ 8-bit
        val upscaled8 = m.mat()
        upscaled.convertTo(upscaled8, CvType.CV_8U, 255.0)

        // تحويل إلى RGBA للإرجاع
        val output = m.mat()
        if (spec.isRgb) {
            Imgproc.cvtColor(upscaled8, output, Imgproc.COLOR_RGB2RGBA)
        } else {
            // للـ grayscale: تحويل Y وتطبيقه مع Cr/Cb الأصلية
            val rgb = m.mat()
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            val ycrcb = m.mat()
            Imgproc.cvtColor(rgb, ycrcb, Imgproc.COLOR_RGB2YCrCb)

            val size = upscaled8.size()
            val cr = m.mat()
            val cb = m.mat()
            Core.extractChannel(ycrcb, cr, 1)
            Core.extractChannel(ycrcb, cb, 2)
            Imgproc.resize(cr, cr, size, 0.0, 0.0, Imgproc.INTER_LINEAR)
            Imgproc.resize(cb, cb, size, 0.0, 0.0, Imgproc.INTER_LINEAR)
            Core.merge(listOf(upscaled8, cr, cb), ycrcb)
            Imgproc.cvtColor(ycrcb, rgb, Imgproc.COLOR_YCrCb2RGB)
            Imgproc.cvtColor(rgb, output, Imgproc.COLOR_RGB2RGBA)
        }

        val bitmap = Bitmap.createBitmap(
            output.cols(),
            output.rows(),
            Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(output, bitmap)
        bitmap
    }

    override fun close() {
        session.close()
    }

    /** معالجة RGB مباشرة (للنماذج التي تأخذ RGB input) */
    private fun upscaleRgb(source: Mat, m: Mats): Mat {
        val scale = spec.scale
        val tileWidth = spec.tileWidth
        val tileHeight = spec.tileHeight
        val coreWidth = tileWidth - 2 * PAD
        val coreHeight = tileHeight - 2 * PAD
        val output = m.own(
            Mat(
                source.rows() * scale,
                source.cols() * scale,
                CvType.CV_32FC3
            )
        )

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

                    // قراءة البيانات (3 channels)
                    val data = FloatArray(tileHeight * tileWidth * 3)
                    tile.get(0, 0, data)

                    val result = runTile(
                        session,
                        spec.inputName,
                        spec.outputName,
                        data,
                        tileHeight,
                        tileWidth,
                        3
                    )
                    val predicted = t.mat(
                        tileHeight * scale,
                        tileWidth * scale,
                        CvType.CV_32FC3
                    )
                    predicted.put(0, 0, result.values)

                    val crop = t.own(
                        Mat(
                            predicted,
                            Rect(
                                PAD * scale,
                                PAD * scale,
                                w * scale,
                                h * scale
                            )
                        )
                    )
                    val destination = t.own(
                        Mat(
                            output,
                            Rect(x * scale, y * scale, w * scale, h * scale)
                        )
                    )
                    crop.copyTo(destination)
                }
                x += w
            }
            y += h
        }
        return output
    }

    /** معالجة Grayscale (النموذج الأصلي) */
    private fun upscaleLuma(source: Mat, m: Mats): Mat {
        val scale = spec.scale
        val tileWidth = spec.tileWidth
        val tileHeight = spec.tileHeight
        val coreWidth = tileWidth - 2 * PAD
        val coreHeight = tileHeight - 2 * PAD
        val output = m.own(
            Mat(source.rows() * scale, source.cols() * scale, CvType.CV_32F)
        )

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

                    val result = runTile(
                        session,
                        spec.inputName,
                        spec.outputName,
                        data,
                        tileHeight,
                        tileWidth,
                        1
                    )
                    val predicted = t.mat(
                        tileHeight * scale,
                        tileWidth * scale,
                        CvType.CV_32F
                    )
                    predicted.put(0, 0, result.values)

                    val crop = t.own(
                        Mat(
                            predicted,
                            Rect(
                                PAD * scale,
                                PAD * scale,
                                w * scale,
                                h * scale
                            )
                        )
                    )
                    val destination = t.own(
                        Mat(
                            output,
                            Rect(x * scale, y * scale, w * scale, h * scale)
                        )
                    )
                    crop.copyTo(destination)
                }
                x += w
            }
            y += h
        }
        return output
    }

    /** التحقق المحسّن: يدعم نماذج متعددة */
    private fun inspect(session: OrtSession): Spec {
        check(session.numInputs >= 1L && session.numOutputs >= 1L) {
            "Expected at least one input and one output"
        }

        val inputName = session.inputNames.first()
        val outputName = session.outputNames.first()

        val shape = (session.inputInfo.getValue(inputName).info as? TensorInfo)?.shape
            ?: error("The model input is not a tensor")

        check(shape.size == 4) { "Expected NCHW format (4D tensor)" }

        val channels = shape[1].toInt()
        check(channels in listOf(1, 3)) { 
            "Expected 1-channel (grayscale) or 3-channel (RGB) input, got $channels"
        }

        val height = if (shape[2] > 0) shape[2].toInt() else DEFAULT_TILE
        val width = if (shape[3] > 0) shape[3].toInt() else DEFAULT_TILE
        check(width > 2 * PAD && height > 2 * PAD) { 
            "The model input is too small to tile" 
        }

        val probe = runTile(
            session,
            inputName,
            outputName,
            FloatArray(height * width * channels),
            height,
            width,
            channels
        ).shape

        check(probe.size == 4 && probe[0] == 1L) { 
            "Unsupported model output shape: ${probe.joinToString()}" 
        }

        // حساب scale من نسبة الحجم
        val scaleH = (probe[2] / height).toInt()
        val scaleW = (probe[3] / width).toInt()
        check(scaleH == scaleW && scaleH in 1..8) { 
            "Unsupported scale: H=$scaleH, W=$scaleW" 
        }

        return Spec(
            inputName = inputName,
            outputName = outputName,
            tileWidth = width,
            tileHeight = height,
            scale = scaleH,
            channels = channels,
            isRgb = channels == 3
        )
    }

    private fun runTile(
        session: OrtSession,
        inputName: String,
        outputName: String,
        
