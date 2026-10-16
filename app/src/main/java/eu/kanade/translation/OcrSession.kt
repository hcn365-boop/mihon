package eu.kanade.translation

import android.content.Context
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import eu.kanade.translation.recognizer.MlKitTextRecognizer
import eu.kanade.translation.recognizer.PaddleTextRecognizer
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Owns the OCR engines needed for [mode] and gives out a ready [PageTextExtractor].
 * Engines are created the first time they are used, so FALLBACK only loads Paddle when ML Kit finds nothing.
 * Close it when the work is done to release the engines' memory.
 */
class OcrSession(
    private val context: Context,
    private val language: TextRecognizerLanguage,
    mode: OcrMode,
) : Closeable {

    init {
        // سجل حاسم: يؤكد الوضع الذي وصل فعلاً إلى الجلسة (يظهر حتى في النسخ النهائية)
        Log.e("OcrSession", ">>> CREATED mode=$mode language=$language <<<")
    }

    private val mlKitEngine = lazy { MlKitTextRecognizer(language) }
    private val paddleEngine = lazy {
        Log.e("OcrSession", ">>> Paddle engine LAZY-INITIALIZED for $language <<<")
        PaddleTextRecognizer(context, language)
    }

    val extractor = PageTextExtractor(
        mode = mode,
        isRtl = PageTextExtractor.isRtlLanguage(language.name),
        mlKit = if (mode == OcrMode.PADDLE) null else this::runMlKit,
        paddle = if (mode == OcrMode.MLKIT) null else this::runPaddle,
    )

    // Both engines block the calling thread, so they run on the default dispatcher.
    private suspend fun runMlKit(image: InputImage): Text {
        Log.e("OcrSession", ">>> runMlKit CALLED <<<")
        return withContext(Dispatchers.Default) { mlKitEngine.value.process(image) }
    }

    private suspend fun runPaddle(image: InputImage): List<OcrBox> {
        // سجل حاسم: إذا ظهر هذا السطر فالوضع Paddle فعّال فعلاً ووصل إلى المحرك
        Log.e("OcrSession", ">>> runPaddle CALLED, delegating to PaddleTextRecognizer <<<")
        return withContext(Dispatchers.Default) {
            paddleEngine.value.process(image).map {
                OcrBox(text = it.text, x = it.x, y = it.y, width = it.width, height = it.height)
            }
        }
    }

    override fun close() {
        if (mlKitEngine.isInitialized()) mlKitEngine.value.close()
        if (paddleEngine.isInitialized()) paddleEngine.value.close()
    }
}
