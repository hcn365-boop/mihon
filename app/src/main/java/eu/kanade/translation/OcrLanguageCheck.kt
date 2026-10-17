package eu.kanade.translation

import com.google.mlkit.nl.languageid.LanguageIdentification
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.cancellation.CancellationException

/** Finds out which language a text is written in, using the on-device ML Kit Language Identification. */
object OcrLanguageCheck {

    private const val MIN_CONFIDENCE = 0.6f

    private val identifier by lazy { LanguageIdentification.getClient() }

    /**
     * @return the page language the text is written in, or null when the text is empty, the model is
     * not sure enough, or the language is not one the OCR has models for
     */
    suspend fun detect(text: String): TextRecognizerLanguage? {
        if (text.isBlank()) return null
        return try {
            val best = identifier.identifyPossibleLanguages(text).await().maxByOrNull { it.confidence }
                ?: return null
            if (best.languageTag == "und" || best.confidence < MIN_CONFIDENCE) {
                null
            } else {
                AutoLanguage.languageFromTag(best.languageTag)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
