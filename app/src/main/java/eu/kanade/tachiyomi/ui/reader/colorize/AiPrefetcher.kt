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
 * A page that gets only part of the work is not finished here: only the colorizer result is
 * stored (cheap to reuse later), because a half-processed image would not be shown.
 */
object AiPrefetcher {

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
            val (page, full) = nextTarget() ?: return
            val open = page.stream ?: continue
            val source = try {
                open().use { Buffer().readFrom(it) }
            } catch (e: Exception) {
                continue
            }
            PageColorizer.prefetch(context, source, full)
        }
    }

    /** The nearest downloaded page that still needs work, and whether it gets the full treatment. */
    private fun nextTarget(): Pair<ReaderPage, Boolean>? {
        synchronized(lock) {
            val page = current ?: return null
            val pages = page.chapter.pages ?: return null

            val signature = "${AiConfig.colorizeEnabled}${AiConfig.colorizeIntensity}${AiConfig.upscaleEnabled}"
            if (signature != doneSignature || done.size > 64) {
                done.clear()
                doneSignature = signature
            }

            val colorAhead = if (AiConfig.colorizeEnabled) AiConfig.colorizePreload else 0
            val upscaleAhead = if (AiConfig.upscaleEnabled) AiConfig.upscalePreload else 0
            for (distance in 1..maxOf(colorAhead, upscaleAhead)) {
                val candidate = pages.getOrNull(page.index + distance) ?: break
                if (candidate.stream == null || candidate in done) continue

                val color = AiConfig.colorizeEnabled && distance <= colorAhead
                val upscale = AiConfig.upscaleEnabled && distance <= upscaleAhead
                val full = color == AiConfig.colorizeEnabled && upscale == AiConfig.upscaleEnabled
                when {
                    full -> {
                        done.add(candidate)
                        return candidate to true
                    }
                    color -> {
                        done.add(candidate)
                        return candidate to false
                    }
                    // Upscale without the colorizer result would be thrown away, so it is skipped.
                }
            }
            return null
        }
    }
}
