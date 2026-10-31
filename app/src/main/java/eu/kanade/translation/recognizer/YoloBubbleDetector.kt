package eu.kanade.translation.recognizer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.min

/**
 * كلاس مسؤول عن تشغيل نموذج Manga-Bubble-YOLO بدقة عالية باستخدام
 * تقنية Letterboxing للحفاظ على أبعاد الصفحة وحساب الإحداثيات الأصلية.
 */
class YoloBubbleDetector(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelBytes = context.assets.open("yolo/yolo26n.onnx").readBytes()
        session = env.createSession(modelBytes)
    }

    data class BubbleBox(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val confidence: Float
    )

    fun detectBubbles(bitmap: Bitmap, confidenceThreshold: Float = 0.25f): List<BubbleBox> {
        val origWidth = bitmap.width.toFloat()
        val origHeight = bitmap.height.toFloat()
        val inputSize = 640

        // 1. حساب معامل التكبير/التصغير مع الحفاظ على نسبة الأبعاد (Aspect Ratio)
        val scale = min(inputSize / origWidth, inputSize / origHeight)
        val scaledW = (origWidth * scale).toInt()
        val scaledH = (origHeight * scale).toInt()

        // 2. تغيير مقاس الصورة مع الحفاظ على النسبة
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)

        // 3. إنشآء لوحة مربعية (640x640) بإطار أبيض (Letterbox) ووضع الصورة في المنتصف
        val letterboxedBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxedBitmap)
        canvas.drawColor(Color.WHITE)

        val padX = (inputSize - scaledW) / 2f
        val padY = (inputSize - scaledH) / 2f
        canvas.drawBitmap(scaledBitmap, padX, padY, null)

        // 4. تجهيز الـ Tensor المدخل (Normalization + RGB Channel Ordering)
        val tensorData = FloatBuffer.allocate(1 * 3 * inputSize * inputSize)
        val intValues = IntArray(inputSize * inputSize)
        letterboxedBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        for (i in intValues.indices) {
            val pixel = intValues[i]
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            tensorData.put(i, r)
            tensorData.put(inputSize * inputSize + i, g)
            tensorData.put(2 * inputSize * inputSize + i, b)
        }
        tensorData.rewind()

        val inputShape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        val inputTensor = OnnxTensor.createTensor(env, tensorData, inputShape)
        val inputName = session.inputNames.iterator().next()

        // 5. تشغيل النموذج
        val results = session.run(mapOf(inputName to inputTensor))
        val rawOutput = results[0].value as Array<*>

        // 6. استخراج النتائج وإعادة طرح مسافات الـ Padding والـ Scale لاستعادة الإحداثيات الأصلية
        val boxes = parseYoloOutput(
            rawOutput = rawOutput,
            scale = scale,
            padX = padX,
            padY = padY,
            origWidth = origWidth,
            origHeight = origHeight,
            threshold = confidenceThreshold
        )

        // تنظيف الذاكرة
        inputTensor.close()
        results.close()
        if (scaledBitmap !== bitmap) scaledBitmap.recycle()
        letterboxedBitmap.recycle()

        return boxes
    }

    private fun parseYoloOutput(
        rawOutput: Array<*>,
        scale: Float,
        padX: Float,
        padY: Float,
        origWidth: Float,
        origHeight: Float,
        threshold: Float
    ): List<BubbleBox> {
        val boxes = mutableListOf<BubbleBox>()
        try {
            val outputTensor = rawOutput[0] as? Array<FloatArray> ?: return boxes
            val numDetections = outputTensor[0].size

            for (i in 0 until numDetections) {
                val score = outputTensor[4][i]
                if (score >= threshold) {
                    val xc = outputTensor[0][i]
                    val yc = outputTensor[1][i]
                    val w = outputTensor[2][i]
                    val h = outputTensor[3][i]

                    // إلغاء تأثير الـ Letterbox للعودة إلى أبعاد الصفحة الأصلية
                    val realXc = (xc - padX) / scale
                    val realYc = (yc - padY) / scale
                    val realW = w / scale
                    val realH = h / scale

                    val x1 = (realXc - realW / 2f).coerceIn(0f, origWidth)
                    val y1 = (realYc - realH / 2f).coerceIn(0f, origHeight)
                    val finalW = realW.coerceAtMost(origWidth - x1)
                    val finalH = realH.coerceAtMost(origHeight - y1)

                    boxes.add(BubbleBox(x1, y1, finalW, finalH, score))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return boxes
    }

    fun close() {
        try {
            session.close()
            env.close()
        } catch (_: Throwable) {}
    }
}
