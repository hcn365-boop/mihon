package eu.kanade.tachiyomi.ui.reader.colorize

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.core.common.preference.Preference

/**
 * The AI settings, either the general ones ([mangaId] null) or the copy that belongs to one manga.
 *
 * Settings that describe how a manga should look (colorize, upscale, translation, language...) can be
 * kept per manga. Settings that belong to the phone or to a service account (memory, cache, threads,
 * hardware, model links, API key, server, request limits) are always the general ones.
 */
class AiPrefs(private val base: ReaderPreferences, private val mangaId: Long?) {

    private fun <T> own(pref: Preference<T>): Preference<T> =
        if (mangaId != null) base.scoped(pref, mangaId) else pref

    // region Can belong to one manga

    val colorizeEnabled: Preference<Boolean> = own(base.colorizeEnabled)
    val colorizeIntensity: Preference<Int> = own(base.colorizeIntensity)
    val colorizeMaxMegapixels: Preference<Int> = own(base.colorizeMaxMegapixels)
    val colorizePreloadCount: Preference<Int> = own(base.colorizePreloadCount)

    val upscaleEnabled: Preference<Boolean> = own(base.upscaleEnabled)
    val upscaleMaxMegapixels: Preference<Int> = own(base.upscaleMaxMegapixels)
    val upscaleMaxOutputMegapixels: Preference<Int> = own(base.upscaleMaxOutputMegapixels)
    val upscaleNormalizeEnabled: Preference<Boolean> = own(base.upscaleNormalizeEnabled)
    val upscaleNormalizeShortSide: Preference<Int> = own(base.upscaleNormalizeShortSide)
    val upscalePreloadCount: Preference<Int> = own(base.upscalePreloadCount)

    val translateEnabled: Preference<Boolean> = own(base.translateEnabled)
    val translateFrom: Preference<String> = own(base.translateFrom)
    val translateTo: Preference<String> = own(base.translateTo)
    val translationEngine: Preference<String> = own(base.translationEngine)
    val translationOcrMode: Preference<String> = own(base.translationOcrMode)
    val translationFont: Preference<String> = own(base.translationFont)
    val translatePreloadCount: Preference<Int> = own(base.translatePreloadCount)
    val translateAutoLanguage: Preference<Boolean> = own(base.translateAutoLanguage)
    val translateAutoFromTitle: Preference<Boolean> = own(base.translateAutoFromTitle)
    val translateAutoFromSource: Preference<Boolean> = own(base.translateAutoFromSource)
    val translateConfirmLanguage: Preference<Boolean> = own(base.translateConfirmLanguage)
    val translateCollisionPadding: Preference<Int> = own(base.translateCollisionPadding) // <--- أضيفت هنا

    // endregion

    // region Always general

    val colorizeModelUrl: Preference<String> = base.colorizeModelUrl
    val upscaleModelUrl: Preference<String> = base.upscaleModelUrl
    val colorizeAcceleration: Preference<String> = base.colorizeAcceleration
    val upscaleAcceleration: Preference<String> = base.upscaleAcceleration
    val aiThreads: Preference<Int> = base.aiThreads
    val aiMemoryBudgetMb: Preference<Int> = base.aiMemoryBudgetMb
    val aiCacheSizeMb: Preference<Int> = base.aiCacheSizeMb
    val pagePreloadCount: Preference<Int> = base.pagePreloadCount
    val translationModel: Preference<String> = base.translationModel
    val translationApiKey: Preference<String> = base.translationApiKey
    val translationTemperature: Preference<String> = base.translationTemperature
    val translationMaxTokens: Preference<String> = base.translationMaxTokens
    val translationServerUrl: Preference<String> = base.translationServerUrl
    val translatePagesPerRequest: Preference<Int> = base.translatePagesPerRequest
    val translateMaxChars: Preference<Int> = base.translateMaxChars
    val translateMaxWaitSeconds: Preference<Int> = base.translateMaxWaitSeconds

    // endregion

    /** The translation settings; any change to one of them rebuilds the translation snapshot. */
    val translationPrefs: List<Preference<*>> = listOf(
        translateEnabled, translateFrom, translateTo, translationEngine, translationOcrMode, translationFont,
        translatePreloadCount, translateAutoLanguage, translateAutoFromTitle, translateAutoFromSource,
        translateConfirmLanguage, translationModel, translationApiKey, translationTemperature,
        translationMaxTokens, translationServerUrl, translatePagesPerRequest, translateMaxChars,
        translateMaxWaitSeconds, translateCollisionPadding, // <--- أضيفت هنا أيضاً لكي تُحدث الـ Snapshot عند تغييرها
    )

    /** Puts the settings of this scope back to their defaults. Keys, server addresses and model links are kept. */
    fun resetToDefaults() {
        fun <T> reset(pref: Preference<T>) = pref.set(pref.defaultValue())
        reset(colorizeEnabled)
        reset(colorizeIntensity)
        reset(colorizeMaxMegapixels)
        reset(colorizePreloadCount)
        reset(upscaleEnabled)
        reset(upscaleMaxMegapixels)
        reset(upscaleMaxOutputMegapixels)
        reset(upscaleNormalizeEnabled)
        reset(upscaleNormalizeShortSide)
        reset(upscalePreloadCount)
        reset(translateEnabled)
        reset(translateFrom)
        reset(translateTo)
        reset(translationEngine)
        reset(translationOcrMode)
        reset(translationFont)
        reset(translatePreloadCount)
        reset(translateAutoLanguage)
        reset(translateAutoFromTitle)
        reset(translateAutoFromSource)
        reset(translateConfirmLanguage)
        reset(colorizeAcceleration)
        reset(upscaleAcceleration)
        reset(aiThreads)
        reset(aiMemoryBudgetMb)
        reset(aiCacheSizeMb)
        reset(pagePreloadCount)
        reset(translationTemperature)
        reset(translationMaxTokens)
        reset(translatePagesPerRequest)
        reset(translateMaxChars)
        reset(translateMaxWaitSeconds)
        reset(translateCollisionPadding)
    }
    
    /** Gives this manga a copy of the general settings, so its own settings start from what you have now. */
    fun copyGeneralSettings() {
        val id = mangaId ?: return
        fun <T> copy(general: Preference<T>) = base.scoped(general, id).set(general.get())
        copy(base.colorizeEnabled)
        copy(base.colorizeIntensity)
        copy(base.colorizeMaxMegapixels)
        copy(base.colorizePreloadCount)
        copy(base.upscaleEnabled)
        copy(base.upscaleMaxMegapixels)
        copy(base.upscaleMaxOutputMegapixels)
        copy(base.upscaleNormalizeEnabled)
        copy(base.upscaleNormalizeShortSide)
        copy(base.upscalePreloadCount)
        copy(base.translateEnabled)
        copy(base.translateFrom)
        copy(base.translateTo)
        copy(base.translationEngine)
        copy(base.translationOcrMode)
        copy(base.translationFont)
        copy(base.translatePreloadCount)
        copy(base.translateAutoLanguage)
        copy(base.translateAutoFromTitle)
        copy(base.translateAutoFromSource)
        copy(base.translateConfirmLanguage)
        copy(base.translateCollisionPadding)
    }
}
