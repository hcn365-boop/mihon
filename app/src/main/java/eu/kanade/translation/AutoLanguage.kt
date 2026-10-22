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
    private val SEPARATORS = Regex("""[,/;|&+\s]+""")

    /** Looks inside the brackets of a title for a language name, such as (Japanese) or [English, Chinese]. */
    fun languageFromTitle(title: String): TextRecognizerLanguage? {
        for (match in BRACKETS.findAll(title)) {
            // Every word inside the brackets is tried; the first known language wins.
            for (word in match.groupValues[1].split(SEPARATORS)) {
                languageFromName(word)?.let { return it }
            }
        }
        return null
    }

    private val NATIVE_NAMES = mapOf(
        "español" to TextRecognizerLanguage.SPANISH,
        "espanol" to TextRecognizerLanguage.SPANISH,
        "français" to TextRecognizerLanguage.FRENCH,
        "francais" to TextRecognizerLanguage.FRENCH,
        "deutsch" to TextRecognizerLanguage.GERMAN,
        "italiano" to TextRecognizerLanguage.ITALIAN,
        "português" to TextRecognizerLanguage.PORTUGUESE,
        "portugues" to TextRecognizerLanguage.PORTUGUESE,
    )

    private fun languageFromName(name: String): TextRecognizerLanguage? {
        val word = name.trim().lowercase()
        return when {
            word in setOf("japanese", "japan", "jp", "ja", "日本語", "日语") -> TextRecognizerLanguage.JAPANESE
            word in setOf("chinese", "china", "cn", "zh", "mandarin", "manhua", "中文", "汉语") ->
                TextRecognizerLanguage.CHINESE
            word in setOf("korean", "korea", "kr", "ko", "manhwa", "한국어") -> TextRecognizerLanguage.KOREAN
            word in setOf("english", "eng", "en") -> TextRecognizerLanguage.ENGLISH
            else -> TextRecognizerLanguage.entries.firstOrNull { it.label.equals(word, ignoreCase = true) }
                ?: NATIVE_NAMES[word]
        }
    }

    /** Maps a language tag ("ja", "zh-hans", "es"...) to a page language, using the translation codes. */
    fun languageFromTag(tag: String): TextRecognizerLanguage? {
        val prefix = tag.substringBefore("-").lowercase()
        return TextRecognizerLanguage.entries.firstOrNull { it.code.equals(prefix, ignoreCase = true) }
    }
}
