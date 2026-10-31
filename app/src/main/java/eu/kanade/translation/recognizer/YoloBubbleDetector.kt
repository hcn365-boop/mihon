package eu.kanade.translation.recognizer

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * كلاس مسؤول عن تشغيل نموذج Manga-Bubble-YOLO (YOLOv8/v26) لاكتشاف
 * فقاعات الحوار في المانجا وتجاهل المؤثرات الصوتية (SFX) الخارجية.
 */
class YoloBubbleDetector(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        // قراءة ملف نموذج YOLO من مجلد الـ assets الذي أضفته
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

    /**
     * يقوم بتحليل الصورة وإرجاع إحداثيات فقاعات الحوار فقط.
     */
    fun detectBubbles(bitmap: Bitmap, confidenceThreshold: Float = 0.3f): List<BubbleBox> {
        val originalWidth = bitmap.width.toFloat()
        val originalHeight = bitmap.height.toFloat()

        // نموذج YOLO يتطلب عادة أبعاد ثابتة للتدخل (مثل 640x640)
        val inputSize = 640
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        // تحضير الـ Tensor بجهد NCHW وتطبيع القيم بين [0, 1]
        val tensorData = FloatBuffer.allocate(1 * 3 * inputSize * inputSize)
        val intValues = IntArray(inputSize * inputSize)
        scaledBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

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

        // تشغيل نموذج الـ AI
        val results = session.run(mapOf(inputName to inputTensor))
        
        // استخراج النتائج وتحليل مخرجات الـ Tensor
        val rawOutput = results[0].value as Array<*>
        val boxes = parseYoloOutput(rawOutput, originalWidth, originalHeight, inputSize.toFloat(), confidenceThreshold)

        // تنظيف الذاكرة لمنع تسريب الموارد
        inputTensor.close()
        results.close()
        if (scaledBitmap !== bitmap) {
            scaledBitmap.recycle()
        }

        return boxes
    }

    private fun parseYoloOutput(
        rawOutput: Array<*>,
        origWidth: Float,
        origHeight: Float,
        inputSize: Float,
        threshold: Float
    ): List<BubbleBox> {
        val boxes = mutableListOf<BubbleBox>()
        try {
            // مخرجات YOLO القياسية بصيغة [1, 4 + classes, N] أو ما يشابهها
            // يتم تحويل الإحداثيات وتطابقها مع مقاسات الصورة الأصلية للمانجا
            val outputTensor = rawOutput[0] as? Array<FloatArray> ?: return boxes
            
            // تحليل وتصفية المربعات بناءً على نسبة الثقة (Confidence)
            // (تختلف بنية المصفوفة قليلاً حسب طريقة تصدير النموذج إلى ONNX، وغالباً تكون الأعمدة ممثلة للإحداثيات)
            val numDetections = outputTensor[0].size
            for (i in 0 until numDetections) {
                val score = outputTensor[4][i] // افتراض مكان درجة الثقة
                if (score >= threshold) {
                    val xc = outputTensor[0][i]
                    val yc = outputTensor[1][i]
                    val w = outputTensor[2][i]
                    val h = outputTensor[3][i]

                    // إعادة التحجيم لتناسب أبعاد الصفحة الأصلية
                    val x1 = (xc - w / 2) * (origWidth / inputSize)
                    val y1 = (yc - h / 2) * (origHeight / inputSize)
                    val ww = w * (origWidth / inputSize)
                    val hh = h * (origHeight / inputSize)

                    boxes.add(BubbleBox(x1, y1, ww, hh, score))
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
