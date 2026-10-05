package eu.kanade.tachiyomi.ui.reader.colorize

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer

/**
 * Runs the Manga Light V6 generator (v6_generator.onnx) on a page.
 *
 * Two steps so the expensive part can be cached:
 *  - [predict] runs the model once and returns only the predicted chroma at model resolution (small).
 *  - [render] combines that chroma with the ORIGINAL page luminance at full size.
 *
 * Not thread safe: call it from one coroutine at a time.
 */
class MangaColorizer(modelFile: File) : Closeable {

    /** Predicted Lab a/b channels at 512x512, in float Lab units. About 2 MB per page. */
    class Chroma(val a: FloatArray, val b: FloatArray)

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }
        session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
        val expectedInputs = setOf(INPUT_IMAGE, INPUT_SAM0, INPUT_SAM1, INPUT_WD14)
        if (session.inputNames != expectedInputs || session.outputNames != setOf(OUTPUT_RGB)) {
            val message = "Not a Manga Light V6 generator: inputs=${session.inputNames}, outputs=${session.outputNames}"
            session.close()
            error(message)
        }
    }

    fun predict(input: Bitmap): Chroma = Mats().use { m ->
        val rgba = m.mat()
        toMat(input, rgba)
        val gray = m.mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val small = m.mat()
        Imgproc.resize(gray, small, Size(SIDE.toDouble(), SIDE.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        // Model input range is [-1, 1].
        small.convertTo(small, CvType.CV_32F, 1.0 / 127.5, -1.0)
        val pixels = FloatArray(PLANE)
        small.get(0, 0, pixels)

        val rgbPlanes = runModel(pixels)
        val planes = List(3) { c ->
            m.mat(SIDE, SIDE, CvType.CV_32F).also { it.put(0, 0, rgbPlanes.copyOfRange(c * PLANE, (c + 1) * PLANE)) }
        }
        val merged = m.mat()
        Core.merge(planes, merged)
        // Model output range is [-1, 1]; bring it to [0, 1] and clamp.
        merged.convertTo(merged, CvType.CV_32F, 0.5, 0.5)
        Core.max(merged, Scalar(0.0, 0.0, 0.0), merged)
        Core.min(merged, Scalar(1.0, 1.0, 1.0), merged)

        val lab = m.mat()
        Imgproc.cvtColor(merged, lab, Imgproc.COLOR_RGB2Lab)
        val a = m.mat()
        val b = m.mat()
        Core.extractChannel(lab, a, 1)
        Core.extractChannel(lab, b, 2)
        Chroma(a.toFloatArray(), b.toFloatArray())
    }

    /**
     * Builds the colored page: luminance from [input], chroma from [chroma] scaled by [intensity]
     * (0 = no color, 1 = model color, up to 1.2). Alpha is preserved.
     */
    fun render(input: Bitmap, chroma: Chroma, intensity: Float = 1f): Bitmap {
        require(intensity in 0f..1.2f) { "intensity must be within 0..1.2" }
        val width = input.width
        val height = input.height
        return Mats().use { m ->
            val rgba = m.mat()
            toMat(input, rgba)
            val rgb = m.mat()
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            // 8-bit Lab: L is 0..255, a and b are offset by 128. Kept 8-bit to save memory on big pages.
            val lab = m.mat()
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
            val luminance = m.mat()
            Core.extractChannel(lab, luminance, 0)

            val a = scaledChroma(m, chroma.a, intensity, width, height)
            val b = scaledChroma(m, chroma.b, intensity, width, height)
            Core.merge(listOf(luminance, a, b), lab)
            Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)

            val out = m.mat()
            Imgproc.cvtColor(rgb, out, Imgproc.COLOR_RGB2RGBA)
            val alpha = m.mat()
            Core.extractChannel(rgba, alpha, 3)
            Core.insertChannel(alpha, out, 3)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(out, bitmap)
            bitmap
        }
    }

    override fun close() {
        session.close()
    }

    /** Returns the model output as 3 consecutive 512x512 planes (R, G, B), values in [-1, 1]. */
    private fun runModel(image: FloatArray): FloatArray {
        val tensors = ArrayList<OnnxTensor>()
        try {
            fun tensor(data: FloatArray, vararg shape: Long): OnnxTensor =
                OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).also { tensors.add(it) }

            val inputs = mapOf(
                INPUT_IMAGE to tensor(image, 1, 1, SIDE.toLong(), SIDE.toLong()),
                // Automatic mode: no SAM features and no WD14 guidance, so these stay zero.
                INPUT_SAM0 to tensor(FloatArray(256 * 32 * 32), 1, 256, 32, 32),
                INPUT_SAM1 to tensor(FloatArray(256 * 16 * 16), 1, 256, 16, 16),
                INPUT_WD14 to tensor(FloatArray(1024), 1, 1024),
            )
            session.run(inputs).use { result ->
                val output = result[0] as OnnxTensor
                val expected = longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())
                require(output.info.shape.contentEquals(expected)) {
                    "Unexpected model output shape: ${output.info.shape.joinToString()}"
                }
                val values = FloatArray(3 * PLANE)
                output.floatBuffer.get(values)
                return values
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }

    private fun scaledChroma(m: Mats, values: FloatArray, intensity: Float, width: Int, height: Int): Mat {
        val small = m.mat(SIDE, SIDE, CvType.CV_32F)
        small.put(0, 0, values)
        val small8 = m.mat()
        small.convertTo(small8, CvType.CV_8U, intensity.toDouble(), 128.0)
        val full = m.mat()
        Imgproc.resize(small8, full, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        return full
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

    private fun Mat.toFloatArray(): FloatArray = FloatArray(PLANE).also { get(0, 0, it) }

    /** Releases every Mat created through it, even if processing throws. */
    private class Mats : Closeable {
        private val owned = ArrayList<Mat>()

        fun mat(): Mat = Mat().also { owned.add(it) }

        fun mat(rows: Int, cols: Int, type: Int): Mat = Mat(rows, cols, type).also { owned.add(it) }

        override fun close() {
            owned.forEach { it.release() }
        }
    }

    private companion object {
        const val SIDE = 512
        const val PLANE = SIDE * SIDE
        const val INPUT_IMAGE = "L_bw"
        const val INPUT_SAM0 = "sam_level0"
        const val INPUT_SAM1 = "sam_level1"
        const val INPUT_WD14 = "wd14_embedding"
        const val OUTPUT_RGB = "rgb_pred"
    }
}
