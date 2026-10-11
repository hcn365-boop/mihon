package eu.kanade.translation

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import eu.kanade.tachiyomi.R

/**
 * Fonts for the translated text. The choice is stored by name, so reordering or adding fonts
 * never changes a saved setting. SYSTEM uses the phone's font, which covers every script
 * (the comic fonts below only have Latin letters).
 */
enum class TranslationFont(val label: String, val res: Int) {
    SYSTEM("System default", 0),
    ANIME_ACE("Anime Ace", R.font.animeace),
    MANGA_MASTER_BB("Manga Master BB", R.font.manga_master_bb),
    COMIC_FONT("Comic Font", R.font.comic_book),
    ;

    fun typeface(context: Context): Typeface =
        if (res == 0) Typeface.DEFAULT else ResourcesCompat.getFont(context, res) ?: Typeface.DEFAULT

    companion object {
        fun fromName(name: String?): TranslationFont =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SYSTEM
    }
}
