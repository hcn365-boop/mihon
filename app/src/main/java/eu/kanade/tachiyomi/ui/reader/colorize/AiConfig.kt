package eu.kanade.tachiyomi.ui.reader.colorize

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.translation.AutoLanguage
import eu.kanade.translation.AutoLanguageOptions
import eu.kanade.translation.BatchLimits
import eu.kanade.translation.MangaTranslationContext
import eu.kanade.translation.OcrMode
import eu.kanade.translation.PageFilterOptions
import eu.kanade.translation.TranslationFont
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TextTranslators
import eu.kanade.translation.translator.TranslatorSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    val auto: AutoLanguageOptions,
    val filter: PageFilterOptions,
) {
    /** The settings with the page language chosen for the manga being read (when auto language is on). */
    fun effectiveSettings(): TranslatorSettings {
        val language = AutoLanguage.resolve(settings.from, auto, null).language
        return if (language == settings.from) settings else settings.copy(from = language)
    }
}

/**
 * Current AI settings, readable from anywhere (page loaders, holders, background jobs) without
 * dependency injection. They follow the manga being read: the general settings, or the settings of
 * that manga when its "separate settings" box is checked ([AiPrefs] decides which).
 */
object AiConfig {

    @Volatile var colorizeEnabled = false

    /** 0 = no color, 1 = model color, up to 1.2 */
    @Volatile var colorizeIntensity = 1f

    @Volatile var upscaleEnabled = false

    @Volatile var colorizeMaxPixels = 12_000_000L

    @Volatile var upscaleMaxPixels = 3_000_000L

    /** Upscaled pages bigger than this are scaled down: the viewer needs a full buffer per page. */
    @Volatile var upscaleMaxOutputPixels = 12_000_000L

    @Volatile var upscaleNormalizeEnabled = false

    @Volatile var upscaleNormalizeSide = 1080

    /** Short side (pixels) big pages are shrunk to before upscaling, or 0 when this is off. */
    val normalizeSide: Int
        get() = if (upscaleNormalizeEnabled) upscaleNormalizeSide else 0

    /** Estimated memory one page may need while being processed. Bigger pages are left as they are. */
    @Volatile var memoryBudgetBytes = 1024L * 1024 * 1024

    /** Disk cache of processed pages. */
    @Volatile var cacheBytes = 1024L * 1024 * 1024

    /** Pages the loader downloads ahead of the current one. */
    @Volatile var pagePreload = 4

    /** Pages ahead that are colorized / upscaled in the background (used by the preload queue). */
    @Volatile var colorizePreload = 3

    @Volatile var upscalePreload = 2

    @Volatile var colorizeAcceleration = Acceleration.CPU

    @Volatile var upscaleAcceleration = Acceleration.CPU

    /** CPU threads for the AI models; 0 lets ONNX Runtime decide. */
    @Volatile var aiThreads = 0

    /** Translation settings, or null when translation is off. */
    @Volatile var translation: TranslationConfig? = null
    
    @Volatile var translateCollisionPadding: Float = 1.3f
    

    val anyEnabled: Boolean
        get() = colorizeEnabled || upscaleEnabled || upscaleNormalizeEnabled || translation != null

    private var prefsRef: ReaderPreferences? = null
    private var viewerScope: CoroutineScope? = null
    private var onOutputChanged: (() -> Unit)? = null
    private var bindJob: Job? = null
    private var notifyJob: Job? = null

    /** True when the user asked to translate this manga even if the page language is the target language. */
    fun forceTranslate(mangaId: Long): Boolean =
        mangaId >= 0 && prefsRef?.mangaForceTranslate(mangaId)?.get() == true

    /**
     * Copies the preferences now and whenever they change.
     * [onOutputChanged] runs after a change that alters how pages look, so the viewer can reload them.
     */
    fun attach(prefs: ReaderPreferences, scope: CoroutineScope, onOutputChanged: () -> Unit) {
        prefsRef = prefs
        viewerScope = scope
        this.onOutputChanged = onOutputChanged
        bindAll(notify = false)
    }

    /** Call when the manga being read changes, so the right set of settings is used. */
    fun onMangaChanged() {
        bindAll(notify = false)
    }

    /** Several changes can come together, and reloading the pages while images are opening can crash the viewer. */
    private fun notifyChanged() {
        val scope = viewerScope ?: return
        notifyJob?.cancel()
        notifyJob = scope.launch {
            delay(600)
            onOutputChanged?.invoke()
        }
    }

    private fun bindAll(notify: Boolean) {
        val prefs = prefsRef ?: return
        val scope = viewerScope ?: return
        bindJob?.cancel()
        val job = SupervisorJob(scope.coroutineContext[Job])
        bindJob = job
        val bindScope = CoroutineScope(scope.coroutineContext + job)

        val mangaId = MangaTranslationContext.mangaId
        val own = mangaId >= 0 && prefs.mangaOverride(mangaId).get()
        val p = AiPrefs(prefs, if (own) mangaId else null)

        // The first value of a flow is not a change, and equal values are not changes either.
        fun <T> bind(pref: Preference<T>, affectsOutput: Boolean, assign: (T) -> Unit) {
            assign(pref.get())
            pref.changes()
                .distinctUntilChanged()
                .drop(1)
                .onEach {
                    assign(it)
                    if (affectsOutput) notifyChanged()
                }
                .launchIn(bindScope)
        }

        bind(p.colorizeEnabled, true) { colorizeEnabled = it }
        bind(p.colorizeIntensity, true) { colorizeIntensity = it / 100f }
        bind(p.upscaleEnabled, true) { upscaleEnabled = it }
        bind(p.colorizeMaxMegapixels, true) { colorizeMaxPixels = it * 1_000_000L }
        bind(p.upscaleMaxMegapixels, true) { upscaleMaxPixels = it * 1_000_000L }
        bind(p.upscaleMaxOutputMegapixels, true) { upscaleMaxOutputPixels = it * 1_000_000L }
        bind(p.upscaleNormalizeEnabled, true) { upscaleNormalizeEnabled = it }
        bind(p.upscaleNormalizeShortSide, true) { upscaleNormalizeSide = it }
        bind(p.aiMemoryBudgetMb, false) { memoryBudgetBytes = it * 1024L * 1024 }
        bind(p.aiCacheSizeMb, false) { cacheBytes = it * 1024L * 1024 }
        bind(p.pagePreloadCount, false) { pagePreload = it }
        bind(p.colorizePreloadCount, false) { colorizePreload = it }
        bind(p.upscalePreloadCount, false) { upscalePreload = it }
        bind(p.colorizeAcceleration, false) { colorizeAcceleration = Acceleration.fromName(it) }
        bind(p.upscaleAcceleration, false) { upscaleAcceleration = Acceleration.fromName(it) }
        bind(p.aiThreads, false) { aiThreads = it }

        bind(p.translateCollisionPadding, true) { translateCollisionPadding = it / 100f }

        
        // Every translation setting rebuilds the whole snapshot, so it is never half updated.
        translation = buildTranslation(p)
        p.translationPrefs.forEach { pref ->
            pref.changes()
                .distinctUntilChanged()
                .drop(1)
                .onEach {
                    translation = buildTranslation(p)
                    notifyChanged()
                }
                .launchIn(bindScope)
        }

        // The box itself: checking or unchecking it switches every setting to the right set.
        if (mangaId >= 0) {
            prefs.mangaOverride(mangaId).changes()
                .distinctUntilChanged()
                .drop(1)
                .onEach { bindAll(notify = true) }
                .launchIn(bindScope)

            prefs.mangaForceTranslate(mangaId).changes()
                .distinctUntilChanged()
                .drop(1)
                .onEach { notifyChanged() }
                .launchIn(bindScope)
        }

        if (notify) notifyChanged()
    }

    private fun buildTranslation(p: AiPrefs): TranslationConfig? {
        if (!p.translateEnabled.get()) return null
        val from = p.translateFrom.get()
        val to = p.translateTo.get()
        return TranslationConfig(
            settings = TranslatorSettings(
                engine = TextTranslators.fromName(p.translationEngine.get()),
                from = TextRecognizerLanguage.entries.firstOrNull { it.name.equals(from, ignoreCase = true) }
                    ?: TextRecognizerLanguage.entries.first(),
                to = TextTranslatorLanguage.entries.firstOrNull { it.name.equals(to, ignoreCase = true) }
                    ?: TextTranslatorLanguage.entries.first(),
                model = p.translationModel.get(),
                apiKey = p.translationApiKey.get(),
                temperature = p.translationTemperature.get().toFloatOrNull() ?: 1f,
                maxOutputTokens = p.translationMaxTokens.get().toIntOrNull() ?: 8192,
                serverUrl = p.translationServerUrl.get(),
            ),
            ocrMode = OcrMode.entries.firstOrNull { it.name.equals(p.translationOcrMode.get(), ignoreCase = true) }
                ?: OcrMode.MLKIT,
            font = TranslationFont.fromName(p.translationFont.get()),
            limits = BatchLimits(
                maxPages = p.translatePagesPerRequest.get().coerceAtLeast(1),
                maxChars = p.translateMaxChars.get().coerceAtLeast(200),
                maxWaitMillis = p.translateMaxWaitSeconds.get().coerceAtLeast(1) * 1000L,
            ),
            preload = p.translatePreloadCount.get(),
            auto = AutoLanguageOptions(
                enabled = p.translateAutoLanguage.get(),
                fromTitle = p.translateAutoFromTitle.get(),
                fromSource = p.translateAutoFromSource.get(),
                confirmPerPage = p.translateConfirmLanguage.get(),
            ),
            filter = PageFilterOptions(
                words = if (p.translateFilterWordsEnabled.get()) {
                    PageFilterOptions.parseWords(p.translateFilterWords.get())
                } else {
                    emptyList()
                },
                latinEnabled = p.translateLatinFilterEnabled.get(),
                latinMinLetters = p.translateLatinMinLetters.get().coerceIn(1, 10),
                skipSameLanguage = p.translateSkipSameLanguage.get(),
            ),
        )
    }
}
