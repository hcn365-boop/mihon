package eu.kanade.translation

import eu.kanade.translation.recognizer.TextRecognizerLanguage

/** Which automatic ways of choosing the page language are switched on. */
class AutoLanguageOptions(
    /** Master switch: choose the language per manga (title tag, then source language). */
    val enabled: Boolean,
    val fromTitle: Boolean,
    val fromSource: Boolean,
    /** After OCR, check the language of the text itself and redo OCR if it is different. */
    val confirmPerPage: Boolean,
)

class ResolvedLanguage(val language: TextRecognizerLanguage, val definitive: Boolean)

/**
 * What the reader knows about the manga being read. The reader sets it once when it opens a manga;
 * the translation code only reads it.
 */
object MangaTranslationContext {
    @Volatile
    var mangaId: Long = -1L
        private set

    @Volatile
    var title: String = ""
        private set

    /** Language of the source (catalogue), such as "ja", "en" or "zh-hans". */
    @Volatile
    var sourceLang: String = ""
        private set

    fun update(mangaId: Long, title: String, sourceLang: String) {
        this.mangaId = mangaId
        this.title = title
        this.sourceLang = sourceLang
    }
}

object AutoLanguage {

    /**
     * Order of trust:
     * 1. the language chosen by hand for this manga ([manual]);
     * 2. a language named in brackets in the title, like "(Japanese)" or "[Korean]";
     * 3. the language of the source;
     * 4. [fallback], the language set in the settings.
     */
    fun resolve(
        fallback: TextRecognizerLanguage,
        options: AutoLanguageOptions,
        manual: TextRecognizerLanguage?,
        title: String = MangaTranslationContext.title,
        sourceLang: String = MangaTranslationContext.sourceLang,
    ): ResolvedLanguage {
        if (manual != null) return ResolvedLanguage(manual, definitive = true)
        if (!options.enabled) return ResolvedLanguage(fallback, definitive = false)

        if (options.fromTitle) {
            languageFromTitle(title)?.let { return ResolvedLanguage(it, definitive = true) }
        }
        if (options.fromSource) {
            languageFromTag(sourceLang)?.let { return ResolvedLanguage(it, definitive = false) }
        }
        return ResolvedLanguage(fallback, definitive = false)
    }

    private val BRACKETS = Regex("""[\[(]([^\])]+)[\])]""")

    /** Looks inside the brackets of a title for a language name. */
    fun languageFromTitle(title: String): TextRecognizerLanguage? {
        for (match in BRACKETS.findAll(title)) {
            languageFromName(match.groupValues[1])?.let { return it }
        }
        return null
    }

    private fun languageFromName(name: String): TextRecognizerLanguage? = when (name.trim().lowercase()) {
        "japanese", "japan", "jp", "ja", "日本語", "日语" -> TextRecognizerLanguage.JAPANESE
        "chinese", "china", "cn", "zh", "mandarin", "manhua", "中文", "汉语" -> TextRecognizerLanguage.CHINESE
        "korean", "korea", "kr", "ko", "manhwa", "한국어" -> TextRecognizerLanguage.KOREAN
        "english", "eng", "en" -> TextRecognizerLanguage.ENGLISH
        else -> null
    }

    /**
     * Maps a language tag ("ja", "zh-hans", "fr"...) to the page languages the OCR has models for.
     * Every Latin-script language is read with the English models. Used for the source language
     * and for the tags that ML Kit language identification returns.
     */
    fun languageFromTag(tag: String): TextRecognizerLanguage? = when (tag.substringBefore("-").lowercase()) {
        "zh" -> TextRecognizerLanguage.CHINESE
        "ja" -> TextRecognizerLanguage.JAPANESE
        "ko" -> TextRecognizerLanguage.KOREAN
        "en", "fr", "de", "es", "it", "pt", "nl", "pl", "tr", "id",
        "vi", "ro", "hu", "cs", "sk", "hr", "sl", "da", "sv", "no", "fi",
        -> TextRecognizerLanguage.ENGLISH
        else -> null
    }
}
