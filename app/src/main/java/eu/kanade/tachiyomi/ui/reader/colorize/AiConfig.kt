package eu.kanade.tachiyomi.ui.reader.colorize

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import tachiyomi.core.common.preference.Preference

/**
 * Current AI settings, readable from anywhere (page loaders, holders, background jobs)
 * without dependency injection. [attach] keeps it in sync with [ReaderPreferences].
 */
object AiConfig {

    @Volatile var colorizeEnabled = false

    /** 0 = no color, 1 = model color, up to 1.2 */
    @Volatile var colorizeIntensity = 1f

    @Volatile var upscaleEnabled = false

    @Volatile var colorizeMaxPixels = 12_000_000L

    @Volatile var upscaleMaxPixels = 3_000_000L

    /** Estimated memory one page may need while being processed. Bigger pages are left as they are. */
    @Volatile var memoryBudgetBytes = 1024L * 1024 * 1024

    /** Disk cache of processed pages. */
    @Volatile var cacheBytes = 1024L * 1024 * 1024

    /** Pages the loader downloads ahead of the current one. */
    @Volatile var pagePreload = 4

    /** Pages ahead that are colorized / upscaled in the background (used by the preload queue). */
    @Volatile var colorizePreload = 3

    @Volatile var upscalePreload = 2

    val anyEnabled: Boolean
        get() = colorizeEnabled || upscaleEnabled

    /**
     * Copies the preferences now and whenever they change.
     * [onOutputChanged] runs after a change that alters how pages look, so the viewer can reload them.
     */
    fun attach(prefs: ReaderPreferences, scope: CoroutineScope, onOutputChanged: () -> Unit) {
        fun <T> bind(pref: Preference<T>, affectsOutput: Boolean, assign: (T) -> Unit) {
            assign(pref.get())
            pref.changes()
                .onEach {
                    assign(it)
                    if (affectsOutput) onOutputChanged()
                }
                .launchIn(scope)
        }

        bind(prefs.colorizeEnabled, true) { colorizeEnabled = it }
        bind(prefs.colorizeIntensity, true) { colorizeIntensity = it / 100f }
        bind(prefs.upscaleEnabled, true) { upscaleEnabled = it }
        bind(prefs.colorizeMaxMegapixels, true) { colorizeMaxPixels = it * 1_000_000L }
        bind(prefs.upscaleMaxMegapixels, true) { upscaleMaxPixels = it * 1_000_000L }
        bind(prefs.aiMemoryBudgetMb, false) { memoryBudgetBytes = it * 1024L * 1024 }
        bind(prefs.aiCacheSizeMb, false) { cacheBytes = it * 1024L * 1024 }
        bind(prefs.pagePreloadCount, false) { pagePreload = it }
        bind(prefs.colorizePreloadCount, false) { colorizePreload = it }
        bind(prefs.upscalePreloadCount, false) { upscalePreload = it }
    }
}
