package eu.kanade.translation

import android.content.Context
import eu.kanade.translation.recognizer.OcrScript
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import java.io.IOException

/** Which page languages an OCR mode can read. */
object OcrLanguages {

    /** ML Kit reads every script we offer, and the hybrid modes fall back to it when Paddle cannot. */
    fun supports(context: Context, mode: OcrMode, language: TextRecognizerLanguage): Boolean = when (mode) {
        OcrMode.PADDLE -> PaddleSupport.supports(context, language)
        else -> true
    }

    /** The languages [mode] can read: the main ones first, then the others by name. */
    fun available(context: Context, mode: OcrMode): List<TextRecognizerLanguage> {
        val all = TextRecognizerLanguage.entries.filter { supports(context, mode, it) }
        return all.filter { it.primary } + all.filter { !it.primary }.sortedBy { it.label }
    }
}

/**
 * What the Paddle OCR in this app can read, decided by the model files in assets/paddleocr:
 * - Latin-script languages (English and all the others): the shared model rec_en_model.onnx;
 * - Chinese, Japanese, Korean: their own model if it is there; Chinese and Japanese are also read by the
 *   shared model when it is the PP-OCRv6 multilingual one (its dictionary is large);
 * - Korean is not read by PP-OCRv6, so it needs rec_kr_model.onnx and kr_dict.txt.
 */
object PaddleSupport {

    private const val DIR = "paddleocr"
    private const val MULTILINGUAL_DICTIONARY_LINES = 10_000

    @Volatile
    private var multilingualCache: Boolean? = null

    /** The model and dictionary made for this language, or null when the shared ones are used. */
    fun ownFiles(context: Context, language: TextRecognizerLanguage): Pair<String, String>? {
        val prefix = when (language.script) {
            OcrScript.CHINESE -> "ch"
            OcrScript.JAPANESE -> "jp"
            OcrScript.KOREAN -> "kr"
            OcrScript.LATIN -> return null
        }
        val model = "$DIR/rec_${prefix}_model.onnx"
        val dictionary = "$DIR/${prefix}_dict.txt"
        return if (hasAsset(context, model) && hasAsset(context, dictionary)) model to dictionary else null
    }

    fun supports(context: Context, language: TextRecognizerLanguage): Boolean = when (language.script) {
        OcrScript.LATIN -> hasAsset(context, "$DIR/rec_en_model.onnx")
        OcrScript.CHINESE, OcrScript.JAPANESE -> ownFiles(context, language) != null || multilingual(context)
        OcrScript.KOREAN -> ownFiles(context, language) != null
    }

    /** The shared model slot holds the multilingual model when its dictionary has thousands of characters. */
    fun multilingual(context: Context): Boolean =
        multilingualCache ?: countDictionary(context).also { multilingualCache = it }

    private fun countDictionary(context: Context): Boolean = try {
        context.assets.open("$DIR/en_dict.txt").bufferedReader().useLines { it.count() } > MULTILINGUAL_DICTIONARY_LINES
    } catch (e: IOException) {
        false
    }

    private fun hasAsset(context: Context, path: String): Boolean = try {
        context.assets.open(path).close()
        true
    } catch (e: IOException) {
        false
    }
}
