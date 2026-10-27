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
import kotlin.math.max
import kotlin.math.min

/** Something that enlarges (or cleans) a page bitmap. */
interface PageUpscaler : Closeable {
    /** @return a new bitmap; the input is not changed or recycled */
    fun process(input: Bitmap): Bitmap
}

/**
 * Runs Anime4K models converted to ONNX (restore, upscale, upscale + denoise, GAN) one after the other.
 * Each model takes an RGB image (batch, 3, height, width) with any size, in float16 or float32,
 * and returns an image of the same or a larger size (the scale is found by running one small test tile).
 *
 * The page is cut into overlapping tiles, so memory stays small. Between models the page is kept as
 * an 8-bit RGB image, which costs nothing visible and saves memory.
 */
class Anime4kStageUpscaler(files: List<File>, acceleration: Acceleration, threads: Int) : PageUpscaler {

    private class Stage(val session: OrtSession, val inputName: String, val half: Boolean, val scale: Int)

    private class TileOut(val shape: LongArray, val values: FloatArray)

    private val env = OrtEnvironment.getEnvironment()
    private val stages: List<Stage>

    /** How many times the whole chain enlarges each side. */
    val scale: Int

    init {
        check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }
        val opened = ArrayList<Stage>()
        stages = try {
            files.map { openStage(it, acceleration, threads).also { stage -> opened.add(stage) } }
        } catch (e: Throwable) {
            opened.forEach { it.session.close() }
            throw e
        }
        scale = stages.fold(1) { total, stage -> total * stage.scale }
    }

    override fun process(input: Bitmap): Bitmap = Mats().use { m ->
        val rgba = m.mat()
        toMat(input, rgba)
        var current = m.mat()
        Imgproc.cvtColor(rgba, current, Imgproc.COLOR_RGBA2RGB)
        m.free(rgba)
        for (stage in stages) {
            val next = runStage(stage, current, m)
            m.free(current)
            current = next
        }
        val out = m.mat()
        Imgproc.cvtColor(current, out, Imgproc.COLOR_RGB2RGBA)
        val bitmap = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out, bitmap)
        bitmap
    }

    override fun close() {
        stages.forEach { it.session.close() }
    }

    /** 8-bit RGB in, 8-bit RGB out, [Stage.scale] times larger. */
    private fun runStage(stage: Stage, input: Mat, m: Mats): Mat {
        val s = stage.scale
        val output = m.own(Mat(input.rows() * s, input.cols() * s, CvType.CV_8UC3))
        var y = 0
        while (y < input.rows()) {
            val h = min(CORE, input.rows() - y)
            var x = 0
            while (x < input.cols()) {
                val w = min(CORE, input.cols() - x)
                Mats().use { t ->
                    // The tile plus a margin of real pixels; the edge is repeated where the page ends.
                    val x0 = max(0, x - PAD)
                    val y0 = max(0, y - PAD)
                    val x1 = min(input.cols(), x + w + PAD)
                    val y1 = min(input.rows(), y + h + PAD)
                    val left = max(0, PAD - x)
                    val top = max(0, PAD - y)
                    val region = t.own(Mat(input, Rect(x0, y0, x1 - x0, y1 - y0)))
                    val tile8 = t.mat()
                    Core.copyMakeBorder(
                        region, tile8, top, TILE - top - region.rows(), left, TILE - left - region.cols(),
                        Core.BORDER_REPLICATE,
                    )
                    val tile32 = t.mat()
                    tile8.convertTo(tile32, CvType.CV_32F, 1.0 / 255.0)

                    // Mat data is interleaved (RGBRGB...); the model wants planes (RR..GG..BB..).
                    val planes = ArrayList<Mat>()
                    Core.split(tile32, planes)
                    val plane = TILE * TILE
                    val data = FloatArray(3 * plane)
                    val row = FloatArray(plane)
                    planes.forEachIndexed { c, p ->
                        p.get(0, 0, row)
                        row.copyInto(data, c * plane)
                        p.release()
                    }

                    val out = runTile(stage.session, stage.inputName, stage.half, data, TILE, TILE)
                    val outSize = TILE * s
                    val outPlane = outSize * outSize
                    val outPlanes = List(3) { c ->
                        t.mat(outSize, outSize, CvType.CV_32F).also {
                            it.put(0, 0, out.values.copyOfRange(c * outPlane, (c + 1) * outPlane))
                        }
                    }
                    val merged = t.mat()
                    Core.merge(outPlanes, merged)
                    val tileOut8 = t.mat()
                    // Converting to 8-bit saturates, so values outside 0..1 are clamped.
                    merged.convertTo(tileOut8, CvType.CV_8U, 255.0)

                    val crop = t.own(Mat(tileOut8, Rect(PAD * s, PAD * s, w * s, h * s)))
                    val destination = t.own(Mat(output, Rect(x * s, y * s, w * s, h * s)))
                    crop.copyTo(destination)
                }
                x += w
            }
            y += h
        }
        return output
    }

    private fun openStage(file: File, acceleration: Acceleration, threads: Int): Stage {
        val session = OrtSessions.create(env, file.absolutePath, acceleration, threads)
        try {
            val name = session.inputNames.first()
            val info = session.inputInfo.getValue(name).info as? TensorInfo ?: error("${file.name}: input is not a tensor")
            val half = when (info.type) {
                OnnxJavaType.FLOAT16 -> true
                OnnxJavaType.FLOAT -> false
                else -> error("${file.name}: unsupported input type ${info.type}")
            }
            check(info.shape.size == 4 && info.shape[1] == 3L) { "${file.name}: expected an RGB image input" }
            // One blank test tile tells how much the model enlarges.
            val probe = runTile(session, name, half, FloatArray(3 * PROBE * PROBE), PROBE, PROBE).shape
            check(probe.size == 4 && probe[1] == 3L && probe[2] == probe[3] && probe[2] % PROBE == 0L) {
                "${file.name}: unexpected output shape ${probe.joinToString("x")}"
            }
            val scale = (probe[2] / PROBE).toInt()
            check(scale in 1..4) { "${file.name}: unsupported scale $scale" }
            return Stage(session, name, half, scale)
        } catch (e: Throwable) {
            session.close()
            throw e
        }
    }

    private fun runTile(session: OrtSession, name: String, half: Boolean, data: FloatArray, h: Int, w: Int): TileOut {
        val shape = longArrayOf(1, 3, h.toLong(), w.toLong())
        val tensor = if (half) {
            val bytes = ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder())
            val shorts = bytes.asShortBuffer()
            for (v in data) shorts.put(floatToHalf(v))
            OnnxTensor.createTensor(env, bytes, shape, OnnxJavaType.FLOAT16)
        } else {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
        }
        tensor.use { input ->
            session.run(mapOf(name to input)).use { result ->
                val out = result[0] as OnnxTensor
                val values = if (out.info.type == OnnxJavaType.FLOAT16) {
                    val shorts = out.byteBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                    FloatArray(shorts.remaining()) { halfToFloat(shorts.get()) }
                } else {
                    val buffer = out.floatBuffer
                    FloatArray(buffer.remaining()).also { buffer.get(it) }
                }
                return TileOut(out.info.shape, values)
            }
        }
    }

    private fun toMat(bitmap: Bitmap, destination: Mat) {
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
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

        /** Frees a big image as soon as it is no longer needed. */
        fun free(mat: Mat) {
            owned.remove(mat)
            mat.release()
        }

        override fun close() {
            owned.forEach { it.release() }
        }
    }

    companion object {
        private const val TILE = 192
        private const val PAD = 16
        private const val CORE = TILE - 2 * PAD
        private const val PROBE = 32

        /** Opens the model once to check it can be used. Throws with a readable message if not. */
        fun validate(file: File) {
            Anime4kStageUpscaler(listOf(file), Acceleration.CPU, 0).close()
        }

        /** IEEE half precision to float (values here are mostly in 0..1, but the full range is handled). */
        private fun halfToFloat(half: Short): Float {
            val bits = half.toInt() and 0xFFFF
            val sign = (bits and 0x8000) shl 16
            val exponent = (bits shr 10) and 0x1F
            val mantissa = bits and 0x3FF
            return when (exponent) {
                0 -> {
                    val value = mantissa * (1f / 16_777_216f) // subnormal: mantissa * 2^-24
                    if (sign != 0) -value else value
                }
                31 -> Float.intBitsToFloat(sign or 0x7F800000 or (mantissa shl 13))
                else -> Float.intBitsToFloat(sign or ((exponent + 112) shl 23) or (mantissa shl 13))
            }
        }

        /** Float to half precision; numbers too small for half become zero, too large become the maximum. */
        private fun floatToHalf(value: Float): Short {
            val bits = java.lang.Float.floatToRawIntBits(value)
            val sign = (bits ushr 16) and 0x8000
            val exponent = ((bits ushr 23) and 0xFF) - 127 + 15
            val mantissa = bits and 0x7FFFFF
            return when {
                exponent <= 0 -> sign.toShort()
                exponent >= 31 -> (sign or 0x7BFF).toShort()
                else -> (sign or (exponent shl 10) or (mantissa ushr 13)).toShort()
            }
        }
    }
}
