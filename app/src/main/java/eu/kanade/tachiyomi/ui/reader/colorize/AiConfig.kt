package eu.kanade.tachiyomi.ui.reader.colorize

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.translation.BatchLimits
import eu.kanade.translation.OcrMode
import eu.kanade.translation.TranslationFont
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TextTranslators
import eu.kanade.translation.translator.TranslatorSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.Preference

/** Everything the translation stage needs, as one immutable snapshot. Null in [AiConfig] when it is off. */
class TranslationConfig(
    val settings: TranslatorSettings,
    val ocrMode: OcrMode,
    val font: TranslationFont,
    val limits: BatchLimits,
    /** How many pages ahead are read (OCR) and queued for translation in the background. */
    val preload: Int,
)

/**
 * Current AI settings, readable from anywhere (page loaders, holders, background jobs)
 * without dependency injection. [attach] keeps it in sync with [ReaderPreferences].
 */
object AiConfig {

    @Volatile var colorizeEnabled = false

    /** 0 = no color, 1 = model color, up to 1.2 */
    @Volatile var colorizeIntensity = 1f

    @Volatile var upscaleEnabled = false

    @Volatile var upscaleNormalizeEnabled = false

    @Volatile var upscaleNormalizeSide = 1080

    /** Short side (pixels) big pages are shrunk to before upscaling, or 0 when this is off. */
    val normalizeSide: Int
        get() = if (upscaleNormalizeEnabled) upscaleNormalizeSide else 0

    @Volatile var colorizeAcceleration = Acceleration.CPU

    @Volatile var upscaleAcceleration = Acceleration.CPU

    @Volatile var aiThreads = 0

    @Volatile var colorizeMaxPixels = 12_000_000L

    @Volatile var upscaleMaxPixels = 3_000_000L

    /**
     * Hard cap on the upscaled output size, in pixels. Pages whose upscaled result would exceed
     * this are scaled back down before being cached. Bound to a preference in [attach] when one
     * exists; the default keeps upscaled pages at a sane size.
     */
    @Volatile var upscaleMaxOutputPixels = 12_000_000L

    /** Estimated memory one page may need while being processed. Bigger pages are left as they are. */
    @Volatile var memoryBudgetBytes = 1024L * 1024 * 1024

    /** Disk cache of processed pages. */
    @Volatile var cacheBytes = 1024L * 1024 * 1024

    /** Pages the loader downloads ahead of the current one. */
    @Volatile var pagePreload = 4

    /** Pages ahead that are colorized / upscaled in the background (used by the preload queue). */
    @Volatile var colorizePreload = 3

    @Volatile var upscalePreload = 2

    /** Translation settings, or null when translation is off. */
    @Volatile var translation: TranslationConfig? = null

    val anyEnabled: Boolean
        get() = colorizeEnabled || upscaleEnabled || translation != null

    /**
     * Copies the preferences now and whenever they change.
     * [onOutputChanged] runs after a change that alters how pages look, so the viewer can reload them.
     *
     * The first value of a flow is not a change, and several changes can come together.
     * Reloading pages too often while their images are being opened can crash the viewer,
     * so initial emissions are dropped, duplicates ignored, and output-affecting changes
     * are coalesced into a single delayed reload.
     */
    fun attach(prefs: ReaderPreferences, scope: CoroutineScope, onOutputChanged: () -> Unit) {
        // Coalesce bursts of output-affecting changes into one reload, 600 ms after the last one.
        var pending: Job? = null
        fun notifyChanged() {
            pending?.cancel()
            pending = scope.launch {
                delay(600)
                onOutputChanged()
            }
        }

        fun <T> bind(pref: Preference<T>, affectsOutput: Boolean, assign: (T) -> Unit) {
            assign(pref.get())
            pref.changes()
                .distinctUntilChanged()
                .drop(1)
                .onEach {
                    assign(it)
                    if (affectsOutput) notifyChanged()
                }
                .launchIn(scope)
        }

        bind(prefs.colorizeEnabled, true) { colorizeEnabled = it }
        bind(prefs.colorizeIntensity, true) { colorizeIntensity = it / 100f }
        bind(prefs.upscaleEnabled, true) { upscaleEnabled = it }
        bind(prefs.upscaleNormalizeEnabled, true) { upscaleNormalizeEnabled = it }
        bind(prefs.upscaleNormalizeShortSide, true) { upscaleNormalizeSide = it }
        bind(prefs.colorizeAcceleration, false) { colorizeAcceleration = Acceleration.fromName(it) }
        bind(prefs.upscaleAcceleration, false) { upscaleAcceleration = Acceleration.fromName(it) }
        bind(prefs.aiThreads, false) { aiThreads = it }
        bind(prefs.colorizeMaxMegapixels, true) { colorizeMaxPixels = it * 1_000_000L }
        bind(prefs.upscaleMaxMegapixels, true) { upscaleMaxPixels = it * 1_000_000L }
        bind(prefs.aiMemoryBudgetMb, false) { memoryBudgetBytes = it * 1024L * 1024 }
        bind(prefs.aiCacheSizeMb, false) { cacheBytes = it * 1024L * 1024 }
        bind(prefs.pagePreloadCount, false) { pagePreload = it }
        bind(prefs.colorizePreloadCount, false) { colorizePreload = it }
        bind(prefs.upscalePreloadCount, false) { upscalePreload = it }

        // Every translation setting rebuilds the whole snapshot, so it is never half updated.
        val translationPrefs: List<Preference<*>> = listOf(
            prefs.translateEnabled,
            prefs.translateFrom,
            prefs.translateTo,
            prefs.translationEngine,
            prefs.translationModel,
            prefs.translationApiKey,
            prefs.translationTemperature,
            prefs.translationMaxTokens,
            prefs.translationServerUrl,
            prefs.translationOcrMode,
            prefs.translationFont,
            prefs.translatePagesPerRequest,
            prefs.translateMaxChars,
            prefs.translateMaxWaitSeconds,
            prefs.translatePreloadCount,
        )
        translation = buildTranslation(prefs)
        translationPrefs.forEach { pref ->
            pref.changes()
                .distinctUntilChanged()
                .drop(1)
                .onEach {
                    translation = buildTranslation(prefs)
                    notifyChanged()
                }
                .launchIn(scope)
        }
    }

    private fun buildTranslation(prefs: ReaderPreferences): TranslationConfig? {
        if (!prefs.translateEnabled.get()) return null
        val from = prefs.translateFrom.get()
        val to = prefs.translateTo.get()
        return TranslationConfig(
            settings = TranslatorSettings(
                engine = TextTranslators.fromName(prefs.translationEngine.get()),
                from = TextRecognizerLanguage.entries.firstOrNull { it.name.equals(from, ignoreCase = true) }
                    ?: TextRecognizerLanguage.entries.first(),
                to = TextTranslatorLanguage.entries.firstOrNull { it.name.equals(to, ignoreCase = true) }
                    ?: TextTranslatorLanguage.entries.first(),
                model = prefs.translationModel.get(),
                apiKey = prefs.translationApiKey.get(),
                temperature = prefs.translationTemperature.get().toFloatOrNull() ?: 1f,
                maxOutputTokens = prefs.translationMaxTokens.get().toIntOrNull() ?: 8192,
                serverUrl = prefs.translationServerUrl.get(),
            ),
            ocrMode = OcrMode.entries.firstOrNull { it.name.equals(prefs.translationOcrMode.get(), ignoreCase = true) }
                ?: OcrMode.MLKIT,
            font = TranslationFont.fromName(prefs.translationFont.get()),
            limits = BatchLimits(
                maxPages = prefs.translatePagesPerRequest.get().coerceAtLeast(1),
                maxChars = prefs.translateMaxChars.get().coerceAtLeast(200),
                maxWaitMillis = prefs.translateMaxWaitSeconds.get().coerceAtLeast(1) * 1000L,
            ),
            preload = prefs.translatePreloadCount.get(),
        )
    }
}
