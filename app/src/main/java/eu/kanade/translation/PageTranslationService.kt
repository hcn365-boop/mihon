package eu.kanade.translation

import android.content.Context
import android.graphics.Bitmap
import eu.kanade.translation.TranslationStore.Kind
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TranslatorSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.coroutines.cancellation.CancellationException

/**
 * How pages are grouped into translator requests. A request is sent as soon as one limit is reached,
 * or when the oldest waiting page has waited [maxWaitMillis].
 */
data class BatchLimits(
    val maxPages: Int = 4,
    val maxChars: Int = 3000,
    val maxWaitMillis: Long = 8_000,
)

/**
 * Gives the translated text blocks of pages, doing as little work as possible:
 *  1. the page is already translated and stored: return it;
 *  2. its OCR is stored but not the translation: only translate;
 *  3. nothing is stored: OCR, then translate.
 * Each result is saved as soon as it exists, so nothing is lost if the app is closed.
 *
 * Pages that need translating are collected and sent together (see [BatchLimits]), because one page
 * often has only a few short bubbles and a request per page wastes time and API quota.
 * - [enqueue] is for pages that are not on screen yet: it returns at once and the page is sent later.
 * - [translateNow] is for the page being read: it sends everything waiting right away and returns its result.
 */
object PageTranslationService {

    private class Pending(
        val context: Context,
        val name: String,
        val page: PageTranslation,
        val settings: TranslatorSettings,
    ) {
        val done = CompletableDeferred<PageTranslation?>()
        val chars: Int = page.blocks.sumOf { it.text.length }
    }

    private sealed interface Prepared {
        class Ready(val page: PageTranslation?) : Prepared
        class Waiting(val entry: Pending) : Prepared
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ocrMutex = Mutex()
    private val translateMutex = Mutex()
    private val pendingLock = Any()
    private val pending = LinkedHashMap<String, Pending>()
    private var timer: Job? = null

    private var ocrSession: OcrSession? = null
    private var ocrSessionKey = ""
    private var translator: TextTranslator? = null
    private var translatorSettings: TranslatorSettings? = null

    /** For a page that is not on screen yet. Returns once the page is stored or waiting to be sent. */
    suspend fun enqueue(
        context: Context,
        settings: TranslatorSettings,
        ocrMode: OcrMode,
        imageHash: String,
        limits: BatchLimits,
        loadBitmap: () -> Bitmap?,
    ) {
        if (prepare(context.applicationContext, settings, ocrMode, imageHash, loadBitmap) !is Prepared.Waiting) return
        val full = synchronized(pendingLock) {
            pending.size >= limits.maxPages || pending.values.sumOf { it.chars } >= limits.maxChars
        }
        if (full) scope.launch { flush(limits) } else scheduleFlush(limits)
    }

    /**
     * For the page being read: sends it together with every page already waiting, without waiting for
     * more. The request runs on its own, so leaving the page does not cancel it for the others.
     *
     * @param loadBitmap the decoded page; only called when OCR is needed. The caller owns the bitmap.
     * @return the page's blocks with translations, or null if the page has no text
     */
    suspend fun translateNow(
        context: Context,
        settings: TranslatorSettings,
        ocrMode: OcrMode,
        imageHash: String,
        limits: BatchLimits,
        loadBitmap: () -> Bitmap?,
    ): PageTranslation? {
        return when (val prepared = prepare(context.applicationContext, settings, ocrMode, imageHash, loadBitmap)) {
            is Prepared.Ready -> prepared.page
            is Prepared.Waiting -> {
                scope.launch { flush(limits) }
                prepared.entry.done.await()?.takeIf { it.blocks.isNotEmpty() }
            }
        }
    }

    /** Sends whatever is waiting, for example when the preload queue has run out of pages. */
    fun flushNow(limits: BatchLimits) {
        scope.launch { flush(limits) }
    }

    /** Releases the OCR engines and the translator (for example when the reader is closed). */
    suspend fun release() {
        ocrMutex.withLock {
            ocrSession?.close()
            ocrSession = null
            ocrSessionKey = ""
        }
        translateMutex.withLock {
            translator?.close()
            translator = null
            translatorSettings = null
        }
    }

    /** Looks the page up in the store, running OCR if needed. Never waits for a translator. */
    private suspend fun prepare(
        context: Context,
        settings: TranslatorSettings,
        ocrMode: OcrMode,
        imageHash: String,
        loadBitmap: () -> Bitmap?,
    ): Prepared {
        val ocrName = "${imageHash}_${TranslationStore.key(settings.from.name, ocrMode.name)}"
        val translationName = "${ocrName}_${TranslationStore.key(settings.to.name, settings.engine.name, settings.model)}"

        TranslationStore.load(context, Kind.TRANSLATED, translationName)?.let {
            return Prepared.Ready(it.takeIf { page -> page.blocks.isNotEmpty() })
        }
        synchronized(pendingLock) { pending[translationName] }?.let { return Prepared.Waiting(it) }

        val ocrPage = ocrMutex.withLock {
            TranslationStore.load(context, Kind.OCR, ocrName) ?: run {
                val bitmap = loadBitmap() ?: return Prepared.Ready(null)
                val found = session(context, settings.from, ocrMode).extractor.extract(bitmap)
                // A page without text is stored too (as an empty page), so OCR does not run on it again.
                val result = found ?: PageTranslation(imgWidth = bitmap.width.toFloat(), imgHeight = bitmap.height.toFloat())
                // Saved before translating, because the translator fills in the blocks in place.
                TranslationStore.save(context, Kind.OCR, ocrName, result)
                result
            }
        }

        if (ocrPage.blocks.isEmpty()) {
            TranslationStore.save(context, Kind.TRANSLATED, translationName, ocrPage)
            return Prepared.Ready(null)
        }
        val entry = synchronized(pendingLock) {
            pending.getOrPut(translationName) { Pending(context, translationName, ocrPage, settings) }
        }
        return Prepared.Waiting(entry)
    }

    /** Starts a timer so a page never waits longer than [BatchLimits.maxWaitMillis]. */
    private fun scheduleFlush(limits: BatchLimits) {
        synchronized(pendingLock) {
            if (timer?.isActive == true) return
            timer = scope.launch {
                delay(limits.maxWaitMillis)
                flush(limits)
            }
        }
    }

    private suspend fun flush(limits: BatchLimits) {
        translateMutex.withLock {
            val entries = synchronized(pendingLock) { pending.values.toList().also { pending.clear() } }
            for ((settings, group) in entries.groupBy { it.settings }) {
                for (chunk in chunks(group, limits)) translateChunk(settings, chunk)
            }
        }
    }

    /** Splits waiting pages into requests of at most maxPages pages and about maxChars characters. */
    private fun chunks(entries: List<Pending>, limits: BatchLimits): List<List<Pending>> {
        val result = mutableListOf<MutableList<Pending>>()
        var chars = 0
        for (entry in entries) {
            val current = result.lastOrNull()
            if (current == null || current.size >= limits.maxPages || chars + entry.chars > limits.maxChars) {
                // A single page above the character limit still goes alone.
                result.add(mutableListOf(entry))
                chars = entry.chars
            } else {
                current.add(entry)
                chars += entry.chars
            }
        }
        return result
    }

    private suspend fun translateChunk(settings: TranslatorSettings, chunk: List<Pending>) {
        val pages = LinkedHashMap<String, PageTranslation>()
        chunk.forEach { pages[it.name] = it.page }
        try {
            translator(settings).translate(pages)
            chunk.forEach {
                TranslationStore.save(it.context, Kind.TRANSLATED, it.name, it.page)
                it.done.complete(it.page)
            }
        } catch (e: CancellationException) {
            chunk.forEach { it.done.cancel() }
            throw e
        } catch (e: Throwable) {
            // The pages stay untranslated and are tried again when they are opened.
            logcat(LogPriority.ERROR, e)
            chunk.forEach { it.done.completeExceptionally(e) }
        }
    }

    private fun session(context: Context, language: TextRecognizerLanguage, mode: OcrMode): OcrSession {
        val key = "${language.name}_${mode.name}"
        val current = ocrSession
        if (current != null && key == ocrSessionKey) return current
        current?.close()
        ocrSession = null
        return OcrSession(context, language, mode).also {
            ocrSession = it
            ocrSessionKey = key
        }
    }

    private fun translator(settings: TranslatorSettings): TextTranslator {
        val current = translator
        if (current != null && settings == translatorSettings) return current
        current?.close()
        translator = null
        return settings.engine.build(settings).also {
            translator = it
            translatorSettings = settings
        }
    }
}
