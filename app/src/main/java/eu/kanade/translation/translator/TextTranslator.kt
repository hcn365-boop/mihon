package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import java.io.Closeable

interface TextTranslator : Closeable {
    val fromLang: TextRecognizerLanguage
    val toLang: TextTranslatorLanguage

    /** Fills in the translation of every block, in place. Several pages can be sent in one request. */
    suspend fun translate(pages: MutableMap<String, PageTranslation>)
}

/** Everything needed to build a translator, as a plain snapshot (no dependency injection). */
data class TranslatorSettings(
    val engine: TextTranslators,
    val from: TextRecognizerLanguage,
    val to: TextTranslatorLanguage,
    val model: String,
    val apiKey: String,
    val temperature: Float,
    val maxOutputTokens: Int,
    val serverUrl: String,
)

/**
 * The translation engines. The external GGUF engine of the original app is left out for now
 * (it needs native libraries); it can be added back as another entry.
 */
enum class TextTranslators(val label: String) {
    MLKIT("MlKit (On Device)"),
    GOOGLE("Google Translate"),
    GEMINI("Gemini AI [API KEY]"),
    OPENROUTER("OpenRouter [API KEY]"),
    LIBRETRANSLATE("LibreTranslate [Local]"),
    LMSTUDIO("LM Studio [Local]"),
    ;

    fun build(settings: TranslatorSettings): TextTranslator {
        val from = settings.from
        val to = settings.to
        val serverUrl = settings.serverUrl.trimEnd('/')
        return when (this) {
            MLKIT -> MLKitTranslator(from, to)
            GOOGLE -> GoogleTranslator(from, to)
            GEMINI -> GeminiTranslator(
                from,
                to,
                settings.apiKey,
                settings.model,
                settings.maxOutputTokens,
                settings.temperature,
            )
            OPENROUTER -> OpenRouterTranslator(
                from,
                to,
                settings.apiKey,
                settings.model,
                settings.maxOutputTokens,
                settings.temperature,
            )
            LIBRETRANSLATE -> LibreTranslateTranslator(from, to, "$serverUrl:5000/translate")
            LMSTUDIO -> LMStudioTranslator(from, to, "$serverUrl:1234/v1/chat/completions")
        }
    }

    companion object {
        /** Engines are stored by name, so adding or removing one never shifts the saved choice. */
        fun fromName(name: String?): TextTranslators =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MLKIT
    }
}
