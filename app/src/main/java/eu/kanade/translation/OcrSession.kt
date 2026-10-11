package eu.kanade.translation

import android.content.Context
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

    private val mlKitEngine = lazy { MlKitTextRecognizer(language) }
    private val paddleEngine = lazy { PaddleTextRecognizer(context, language) }

    val extractor = PageTextExtractor(
        mode = mode,
        isRtl = PageTextExtractor.isRtlLanguage(language.name),
        mlKit = if (mode == OcrMode.PADDLE) null else this::runMlKit,
        paddle = if (mode == OcrMode.MLKIT) null else this::runPaddle,
    )

    // Both engines block the calling thread, so they run on the default dispatcher.
    private suspend fun runMlKit(image: InputImage): Text =
        withContext(Dispatchers.Default) { mlKitEngine.value.process(image) }

    private suspend fun runPaddle(image: InputImage): List<OcrBox> =
        withContext(Dispatchers.Default) {
            paddleEngine.value.process(image).map {
                OcrBox(text = it.text, x = it.x, y = it.y, width = it.width, height = it.height)
            }
        }

    override fun close() {
        if (mlKitEngine.isInitialized()) mlKitEngine.value.close()
        if (paddleEngine.isInitialized()) paddleEngine.value.close()
    }
}
