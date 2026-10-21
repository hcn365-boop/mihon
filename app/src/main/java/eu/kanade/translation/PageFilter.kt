package eu.kanade.translation

import eu.kanade.translation.model.PageTranslation

/** Which filters run on the text of a page after OCR. */
class PageFilterOptions(
    /** Bubbles whose cleaned text contains one of these (already cleaned) words are removed. */
    val words: List<String>,
    /** Remove Latin bubbles with fewer than [latinMinLetters] different letters. */
    val latinEnabled: Boolean,
    val latinMinLetters: Int,
    /** Do not translate a page that is already in the target language, unless the user asks for it. */
    val skipSameLanguage: Boolean,
) {
    /** Changes whenever the result of the filters could change, so saved results are not reused wrongly. */
    val signature: String
        get() = "w=${words.joinToString(",")};l=$latinEnabled:$latinMinLetters;s=$skipSameLanguage"

    companion object {
        /** Turns what the user typed (separated by commas, semicolons or new lines) into cleaned words. */
        fun parseWords(text: String): List<String> =
            text.split(',', ';', '\n').map { PageFilter.clean(it) }.filter { it.isNotEmpty() }.distinct()
    }
}

/**
 * Removes unwanted bubbles from the text of a page. A removed bubble is not translated and nothing is
 * drawn for it, so its original text stays on the page.
 */
object PageFilter {

    /**
     * The text with digits, punctuation and symbols removed, in lower case. Every run of removed
     * characters becomes one space, so "Credit:SITE.com" becomes "credit site com".
     */
    fun clean(text: String): String {
        val out = StringBuilder()
        for (ch in text) {
            when {
                Character.isLetter(ch) -> out.append(ch.lowercaseChar())
                else -> if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
            }
        }
        return out.toString().trim()
    }

    fun apply(page: PageTranslation, options: PageFilterOptions): PageTranslation {
        if (options.words.isEmpty() && !options.latinEnabled) return page
        val kept = page.blocks.filter { keep(it.text, options) }
        if (kept.size == page.blocks.size) return page
        val result = PageTranslation(imgWidth = page.imgWidth, imgHeight = page.imgHeight)
        result.blocks = kept.toMutableList()
        return result
    }

    private fun keep(text: String, options: PageFilterOptions): Boolean {
        val cleaned = clean(text)
        if (options.words.any { hasWord(cleaned, it) }) return false
        if (options.latinEnabled && isLatin(cleaned) && uniqueLetters(cleaned) < options.latinMinLetters) return false
        return true
    }

    /** A whole word or phrase. Scripts written without spaces (Chinese, Japanese, Korean) match anywhere. */
    private fun hasWord(cleaned: String, word: String): Boolean {
        if (word.any { isUnspaced(it) }) return cleaned.replace(" ", "").contains(word.replace(" ", ""))
        return Regex("(^| )${Regex.escape(word)}( |$)").containsMatchIn(cleaned)
    }

    private fun isUnspaced(ch: Char): Boolean = when (Character.UnicodeScript.of(ch.code)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        -> true
        else -> false
    }

    /** Has letters, and every letter is Latin. Text with no letters at all is not treated as Latin. */
    private fun isLatin(cleaned: String): Boolean {
        val letters = cleaned.filter { it.isLetter() }
        return letters.isNotEmpty() && letters.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }
    }

    private fun uniqueLetters(cleaned: String): Int = cleaned.filter { it.isLetter() }.toSet().size
}
