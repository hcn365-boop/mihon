package eu.kanade.tachiyomi.ui.reader.colorize

import android.content.Context
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Buffer

/**
 * Processes the pages after the one being read, in the background, so they are already done
 * (and cached) when the reader gets to them.
 *
 * One page at a time, nearest first, so a page the user opens never waits behind more than one job.
 * Only pages that are already downloaded are processed. Distances come from [AiConfig]:
 * a page `d` pages ahead is colorized if d <= colorizePreload and upscaled if d <= upscalePreload.
 * Its text is read and queued for translation if d <= the translation preload (see [TranslationConfig]).
 *
 * A page gets the translation work only together with the full treatment, so the translation preload
 * should not be larger than the colorize and upscale preloads that are switched on.
 * A page that gets only part of the work is not finished here: only the colorizer result is
 * stored (cheap to reuse later), because a half-processed image would not be shown.
 */
object AiPrefetcher {

    private class Target(val page: ReaderPage, val full: Boolean, val translate: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var appContext: Context? = null
    private var current: ReaderPage? = null
    private var worker: Job? = null
    private var doneSignature = ""
    private val done = HashSet<ReaderPage>()

    /** Called by the page holders once a page has been shown/loaded. */
    fun onPageShown(context: Context, page: ReaderPage) {
        synchronized(lock) {
            appContext = context.applicationContext
            current = page
        }
        kick()
    }

    /** Wakes the worker, e.g. when a page finishes downloading. Safe to call from any thread. */
    fun kick() {
        synchronized(lock) {
            val context = appContext ?: return
            if (!AiConfig.anyEnabled) return
            if (worker?.isActive == true) return
            worker = scope.launch { work(context) }
        }
    }

    private suspend fun work(context: Context) {
        while (true) {
            val target = nextTarget() ?: return
            val open = target.page.stream ?: continue
            val source = try {
                open().use { Buffer().readFrom(it) }
            } catch (e: Exception) {
                continue
            }
            PageColorizer.prefetch(context, source, target.full, target.translate)
        }
    }

    /** The nearest downloaded page that still needs work, and what kind of work it gets. */
    private fun nextTarget(): Target? {
        synchronized(lock) {
            val page = current ?: return null
            val pages = page.chapter.pages ?: return null
            val translation = AiConfig.translation

            val signature = "${AiConfig.colorizeEnabled}${AiConfig.colorizeIntensity}${AiConfig.upscaleEnabled}" +
                "${translation != null}"
            if (signature != doneSignature || done.size > 64) {
                done.clear()
                doneSignature = signature
            }

            val colorAhead = if (AiConfig.colorizeEnabled) AiConfig.colorizePreload else 0
            val upscaleAhead = if (AiConfig.upscaleEnabled) AiConfig.upscalePreload else 0
            val translateAhead = translation?.preload ?: 0
            for (distance in 1..maxOf(colorAhead, upscaleAhead, translateAhead)) {
                val candidate = pages.getOrNull(page.index + distance) ?: break
                if (candidate.stream == null || candidate in done) continue

                val color = AiConfig.colorizeEnabled && distance <= colorAhead
                val upscale = AiConfig.upscaleEnabled && distance <= upscaleAhead
                val translate = translation != null && distance <= translateAhead
                val full = color == AiConfig.colorizeEnabled && upscale == AiConfig.upscaleEnabled
                when {
                    full -> {
                        done.add(candidate)
                        return Target(candidate, full = true, translate = translate)
                    }
                    color -> {
                        done.add(candidate)
                        return Target(candidate, full = false, translate = false)
                    }
                    // Upscale without the colorizer result would be thrown away, so it is skipped.
                }
            }
            return null
        }
    }
}
