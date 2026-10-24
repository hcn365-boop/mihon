package eu.kanade.tachiyomi.ui.reader.colorize

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import ai.onnxruntime.GraphOptimizationLevel
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtLoggingLevel
import android.graphics.Bitmap
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

class Anime4kUpscaler {
    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var spec: Spec? = null

    data class Spec(
        val inputName: String,
        val tileWidth: Int,
        val tileHeight: Int,
        val scale: Int,
    )

    private data class TileResult(val shape: LongArray, val values: FloatArray)

    fun load(modelPath: String): Boolean = runCatching {
        val sessionOptions = OrtSession.SessionOptions()
        sessionOptions.setGraphOptimizationLevel(OrtSession.GraphOptimizationLevel.ORT_ENABLE_ALL)
        session = env.createSession(modelPath, sessionOptions)
        spec = inspect(session!!)
        true
    }.getOrElse { e ->
        android.util.Log.e("Anime4kUpscaler", "Failed to load model: ${e.message}", e)
        false
    }

    fun upscale(bitmap: Bitmap): Bitmap = runCatching {
        val spec = spec ?: error("Model not loaded")
        val session = session ?: error("Model not loaded")

        val (width, height) = bitmap.width to bitmap.height
        val scaledWidth = width * spec.scale
        val scaledHeight = height * spec.scale

        // تحويل Bitmap إلى RGBA floats
        val rgbaPixels = IntArray(width * height)
        bitmap.getPixels(rgbaPixels, 0, width, 0, 0, width, height)

        // استخراج RGB من RGBA
        val rgbData = FloatArray(3 * width * height)
        for (i in rgbaPixels.indices) {
            val pixel = rgbaPixels[i]
            val r = (pixel shr 16 and 0xFF) / 255f
            val g = (pixel shr 8 and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            rgbData[i] = r
            rgbData[width * height + i] = g
            rgbData[2 * width * height + i] = b
        }

        // تحويل RGB إلى YCrCb
        val yCrCbData = FloatArray(3 * width * height)
        for (i in width * height - 1 downTo 0) {
            val r = rgbData[i]
            val g = rgbData[width * height + i]
            val b = rgbData[2 * width * height + i]
            yCrCbData[i] = 0.299f * r + 0.587f * g + 0.114f * b
            yCrCbData[width * height + i] = 0.5f - 0.168736f * r - 0.331264f * g + 0.5f * b
            yCrCbData[2 * width * height + i] = 0.5f + 0.5f * r - 0.418688f * g - 0.081312f * b
        }

        // upscale القناة Y فقط
        val yChannel = yCrCbData.sliceArray(0 until width * height)
        val upscaledY = upscaleTiled(session, spec, yChannel, width, height)

        // تغيير حجم قنوات Cr و Cb
        val crChannel = yCrCbData.sliceArray(width * height until 2 * width * height)
        val cbChannel = yCrCbData.sliceArray(2 * width * height until 3 * width * height)
        val resizedCr = resizeChannel(crChannel, width, height, scaledWidth, scaledHeight)
        val resizedCb = resizeChannel(cbChannel, width, height, scaledWidth, scaledHeight)

        // دمج القنوات YCrCb
        val upscaledYCrCb = FloatArray(3 * scaledWidth * scaledHeight)
        upscaledY.copyInto(upscaledYCrCb, 0)
        resizedCr.copyInto(upscaledYCrCb, scaledWidth * scaledHeight)
        resizedCb.copyInto(upscaledYCrCb, 2 * scaledWidth * scaledHeight)

        // تحويل YCrCb إلى RGB
        val upscaledRgb = FloatArray(3 * scaledWidth * scaledHeight)
        for (i in scaledWidth * scaledHeight - 1 downTo 0) {
            val y = upscaledYCrCb[i]
            val cr = upscaledYCrCb[scaledWidth * scaledHeight + i]
            val cb = upscaledYCrCb[2 * scaledWidth * scaledHeight + i]
            upscaledRgb[i] = (y + 1.402f * (cr - 0.5f)).coerceIn(0f, 1f)
            upscaledRgb[scaledWidth * scaledHeight + i] =
                (y - 0.344136f * (cb - 0.5f) - 0.714136f * (cr - 0.5f)).coerceIn(0f, 1f)
            upscaledRgb[2 * scaledWidth * scaledHeight + i] = (y + 1.772f * (cb - 0.5f)).coerceIn(0f, 1f)
        }

        // تحويل RGB إلى RGBA
        val upscaledRgba = IntArray(scaledWidth * scaledHeight)
        for (i in upscaledRgba.indices) {
            val r = (upscaledRgb[i] * 255).toInt().coerceIn(0, 255)
            val g = (upscaledRgb[scaledWidth * scaledHeight + i] * 255).toInt().coerceIn(0, 255)
            val b = (upscaledRgb[2 * scaledWidth * scaledHeight + i] * 255).toInt().coerceIn(0, 255)
            upscaledRgba[i] = -0x1000000 or (r shl 16) or (g shl 8) or b
        }

        Bitmap.createBitmap(upscaledRgba, scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)
    }.getOrElse { e ->
        android.util.Log.e("Anime4kUpscaler", "Failed to upscale bitmap: ${e.message}", e)
        bitmap
    }

    private fun upscaleTiled(
        session: OrtSession,
        spec: Spec,
        channel: FloatArray,
        width: Int,
        height: Int,
    ): FloatArray {
        val scaledWidth = width * spec.scale
        val scaledHeight = height * spec.scale
        val result = FloatArray(scaledWidth * scaledHeight)

        var y = 0
        while (y < height) {
            val tileHeight = min(spec.tileHeight, height - y)
            var x = 0
            while (x < width) {
                val tileWidth = min(spec.tileWidth, width - x)
                val tile = extractTile(channel, width, x, y, tileWidth, tileHeight)
                val upscaledTile = runTile(session, spec.inputName, tile, tileHeight, tileWidth, 1)
                val trimmed = trimTile(upscaledTile.values, tileWidth * spec.scale, tileHeight * spec.scale)
                insertTile(result, scaledWidth, x * spec.scale, y * spec.scale, trimmed)
                x += tileWidth - 2 * PAD
            }
            y += tileHeight - 2 * PAD
        }

        return result
    }

    private fun extractTile(
        channel: FloatArray,
        width: Int,
        startX: Int,
        startY: Int,
        tileWidth: Int,
        tileHeight: Int,
    ): FloatArray {
        val tile = FloatArray(tileWidth * tileHeight)
        for (dy in 0 until tileHeight) {
            val srcIdx = (startY + dy) * width + startX
            val dstIdx = dy * tileWidth
            channel.copyInto(tile, dstIdx, srcIdx, srcIdx + tileWidth)
        }
        return tile
    }

    private fun insertTile(
        result: FloatArray,
        width: Int,
        startX: Int,
        startY: Int,
        tile: FloatArray,
    ) {
        var srcIdx = 0
        for (y in startY until startY + tile.size / width.coerceAtLeast(1)) {
            if (y >= 0 && y * width + startX + width <= result.size) {
                tile.copyInto(result, y * width + startX, srcIdx, srcIdx + width)
                srcIdx += width
            }
        }
    }

    private fun trimTile(data: FloatArray, width: Int, height: Int): FloatArray {
        val trimmed = FloatArray(width * height)
        for (dy in 0 until height) {
            val srcIdx = dy * width
            trimmed.copyInto(trimmed, dy * width, srcIdx, srcIdx + width)
        }
        return trimmed
    }

    private fun resizeChannel(
        channel: FloatArray,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
    ): FloatArray {
        val result = FloatArray(dstWidth * dstHeight)
        for (dy in 0 until dstHeight) {
            for (dx in 0 until dstWidth) {
                val srcX = (dx * srcWidth) / dstWidth.toFloat()
                val srcY = (dy * srcHeight) / dstHeight.toFloat()
                val x0 = srcX.toInt().coerceIn(0, srcWidth - 1)
                val y0 = srcY.toInt().coerceIn(0, srcHeight - 1)
                result[dy * dstWidth + dx] = channel[y0 * srcWidth + x0]
            }
        }
        return result
    }

    /** Checks the model contract and finds its tile size and scale by running one blank tile. */
    private fun inspect(session: OrtSession): Spec {
        check(session.numInputs == 1L && session.numOutputs == 1L) {
            "Expected a model with one image input and one image output"
        }
        val inputName = session.inputNames.first()
        val inputInfo = session.inputInfo.getValue(inputName).info

        // اكتشاف شكل الإدخال
        val shape = when (inputInfo) {
            is TensorInfo -> inputInfo.shape
            else -> error("The model input is not a tensor")
        }

        check(shape.size == 4) {
            "Expected NCHW image format, got shape: ${shape.joinToString()}"
        }

        val numChannels = shape[1].toInt()
        check(numChannels in listOf(1, 3)) {
            "Expected 1-channel (Y) or 3-channel (RGB) input, got $numChannels channels"
        }

        // اكتشاف حجم البلاط من شكل الإدخال
        val tileHeight = if (shape[2] > 0) shape[2].toInt() else DEFAULT_TILE
        val tileWidth = if (shape[3] > 0) shape[3].toInt() else DEFAULT_TILE

        check(tileWidth > 2 * PAD && tileHeight > 2 * PAD) {
            "The model input is too small to tile: ${tileWidth}x${tileHeight}"
        }

        // تشغيل بلاة اختبارية لاكتشاف المقياس
        val testData = FloatArray(numChannels * tileHeight * tileWidth)
        val probe = runTile(session, inputName, testData, tileHeight, tileWidth, numChannels).shape

        check(
            probe.size == 4 && probe[0] == 1L && probe[1] == numChannels.toLong() &&
                probe[2] % tileHeight == 0L && probe[3] % tileWidth == 0L &&
                probe[2] / tileHeight == probe[3] / tileWidth,
        ) {
            "Unsupported model output shape: ${probe.joinToString()}"
        }

        val scale = (probe[3] / tileWidth).toInt()
        check(scale in 1..4) { "Unsupported scale: $scale (must be between 1 and 4)" }

        return Spec(inputName, tileWidth, tileHeight, scale)
    }

    private fun runTile(
        session: OrtSession,
        inputName: String,
        data: FloatArray,
        height: Int,
        width: Int,
        numChannels: Int = 1,
    ): TileResult {
        val shape = longArrayOf(1, numChannels.toLong(), height.toLong(), width.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val output = result[0] as OnnxTensor
                val buffer = output.floatBuffer
                val values = FloatArray(buffer.remaining())
                buffer.get(values)
                return TileResult(output.info.shape, values)
            }
        }
    }

    fun release() {
        session?.close()
        session = null
        spec = null
    }

    companion object {
        private const val DEFAULT_TILE = 160
        private const val PAD = 16
    }
}
