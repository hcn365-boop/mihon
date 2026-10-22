package eu.kanade.translation.recognizer

import com.google.mlkit.nl.translate.TranslateLanguage
import tachiyomi.core.common.preference.Preference

/** The writing system an OCR model reads. */
enum class OcrScript { LATIN, CHINESE, JAPANESE, KOREAN }

/**
 * The language of the text on a page. It decides which OCR model reads the page ([script]) and which
 * source language the translator is told about ([code], the same codes ML Kit translation uses).
 * The first four are the main ones and are listed first; the others are written in the Latin alphabet
 * and are read with the Latin OCR models.
 */
enum class TextRecognizerLanguage(
    var code: String,
    val label: String,
    val script: OcrScript,
    val primary: Boolean = false,
) {
    CHINESE(TranslateLanguage.CHINESE, "Chinese (trad/sim)", OcrScript.CHINESE, primary = true),
    JAPANESE(TranslateLanguage.JAPANESE, "Japanese", OcrScript.JAPANESE, primary = true),
    KOREAN(TranslateLanguage.KOREAN, "Korean", OcrScript.KOREAN, primary = true),
    ENGLISH(TranslateLanguage.ENGLISH, "English", OcrScript.LATIN, primary = true),

    SPANISH("es", "Spanish", OcrScript.LATIN),
    FRENCH("fr", "French", OcrScript.LATIN),
    GERMAN("de", "German", OcrScript.LATIN),
    ITALIAN("it", "Italian", OcrScript.LATIN),
    PORTUGUESE("pt", "Portuguese", OcrScript.LATIN),
    DUTCH("nl", "Dutch", OcrScript.LATIN),
    POLISH("pl", "Polish", OcrScript.LATIN),
    TURKISH("tr", "Turkish", OcrScript.LATIN),
    INDONESIAN("id", "Indonesian", OcrScript.LATIN),
    MALAY("ms", "Malay", OcrScript.LATIN),
    VIETNAMESE("vi", "Vietnamese", OcrScript.LATIN),
    ROMANIAN("ro", "Romanian", OcrScript.LATIN),
    HUNGARIAN("hu", "Hungarian", OcrScript.LATIN),
    CZECH("cs", "Czech", OcrScript.LATIN),
    SLOVAK("sk", "Slovak", OcrScript.LATIN),
    CROATIAN("hr", "Croatian", OcrScript.LATIN),
    SLOVENIAN("sl", "Slovenian", OcrScript.LATIN),
    DANISH("da", "Danish", OcrScript.LATIN),
    SWEDISH("sv", "Swedish", OcrScript.LATIN),
    NORWEGIAN("no", "Norwegian", OcrScript.LATIN),
    FINNISH("fi", "Finnish", OcrScript.LATIN),
    CATALAN("ca", "Catalan", OcrScript.LATIN),
    LATVIAN("lv", "Latvian", OcrScript.LATIN),
    LITHUANIAN("lt", "Lithuanian", OcrScript.LATIN),
    ESTONIAN("et", "Estonian", OcrScript.LATIN),
    TAGALOG("tl", "Tagalog", OcrScript.LATIN),
    SWAHILI("sw", "Swahili", OcrScript.LATIN),
    ALBANIAN("sq", "Albanian", OcrScript.LATIN),
    ICELANDIC("is", "Icelandic", OcrScript.LATIN),
    ;

    companion object {
        fun fromPref(pref: Preference<String>): TextRecognizerLanguage {
            val name = pref.get()
            val lang = entries.firstOrNull { it.name.equals(name, true) }
            if (lang == null) {
                pref.set(CHINESE.name)
                return CHINESE
            }
            return lang
        }
    }
}
