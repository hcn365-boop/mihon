package eu.kanade.translation.translator

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

// دالة مساعدة لتحويل Google Tasks إلى coroutines بدون حاجة لمكتبة إضافية
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> cont.resume(result) }
    addOnFailureListener { e -> cont.resumeWithException(e) }
}

class MLKitTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
) : TextTranslator {

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(fromLang.code)
            .setTargetLanguage(TranslateLanguage.fromLanguageTag(toLang.code) ?: TranslateLanguage.ENGLISH)
            .build(),
    )

    private val conditions = DownloadConditions.Builder().build()
    private val urlPattern = Regex("(?i)(https?://\\S+|www\\.\\S+|\\S+\\.(com|net|org|io|me|cc|tv|info))")
    
    // ميزات الأداء من الكود الثاني: تقييد الطلبات المتزامنة وفاصل التجميع للصفحة[span_1](start_span)[span_1](end_span)
    private val translationSemaphore = Semaphore(3)
    private val batchSeparator = " ||| "

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        // --- إعداد منطق إصلاح الاتجاه (RTL) ---
        val rtlLanguages = setOf(
            TextTranslatorLanguage.ARABIC,
            TextTranslatorLanguage.PERSIAN,
            TextTranslatorLanguage.HEBREW,
            TextTranslatorLanguage.URDU,
            TextTranslatorLanguage.PUSHTO_PASHTO,
            TextTranslatorLanguage.SINDHI,
            TextTranslatorLanguage.UIGHUR_UYGHUR,
            TextTranslatorLanguage.YIDDISH,
            TextTranslatorLanguage.KURDISH,
        )
        val isRTL = rtlLanguages.contains(toLang)
        val rtlMarker = if (isRTL) "\u200F" else ""

        try {
            // تحميل الموديل مرة واحدة في البداية
            translator.downloadModelIfNeeded(conditions).await()

            withContext(Dispatchers.IO) {
                supervisorScope {
                    pages.values.forEach { page ->
                        // معالجة كل صفحة بشكل متوازٍ باستخدام coroutines[span_2](start_span)[span_2](end_span)
                        launch {
                            try {
                                // 1. تصفية ذكية محسنة وقبول مرن للزوايا والنصوص الصالحة
                                val validBlocks = page.blocks.filter { block ->
                                    val angle = abs(block.angle)
                                    val isAcceptedAngle = angle <= 15.0f || angle >= 75.0f
                                    isAcceptedAngle && !urlPattern.containsMatchIn(block.text) && block.text.isNotBlank()
                                }

                                if (validBlocks.isEmpty()) {
                                    page.blocks.forEach { it.translation = "" }
                                    return@launch
                                }

                                // 2. تنظيف النصوص ومعالجة النصوص العمودية والأفقية لكل بلوك
                                val cleanedTexts = validBlocks.map { block ->
                                    var cleanText = block.text.trim()
                                    val isVerticalText = abs(block.angle) in 65.0f..115.0f

                                    if (isVerticalText) {
                                        cleanText = cleanText.replace(Regex("[ー\u2014\u2015]"), "")
                                        cleanText = cleanText.replace(Regex("\\s+"), "")
                                    } else {
                                        cleanText = cleanText.replace(Regex("\\s+"), " ")
                                    }
                                    cleanText
                                }

                                // 3. تجميع النصوص في طلب شبكة/ترجمة واحد للصفحة باستخدام الفاصل المميز[span_3](start_span)[span_3](end_span)
                                val batchedText = cleanedTexts.joinToString(batchSeparator)

                                val translatedBatchedText = translationSemaphore.withPermit {
                                    translator.translate(batchedText).await()
                                }

                                // 4. تقسيم النص المترجم وإعادته للكتل الأصلية[span_4](start_span)[span_4](end_span)
                                val translatedParts = translatedBatchedText.split(batchSeparator)

                                if (translatedParts.size == validBlocks.size) {
                                    validBlocks.forEachIndexed { index, block ->
                                        val translated = translatedParts[index].trim()
                                        block.translation = if (translated.isNotBlank()) {
                                            rtlMarker + translated
                                        } else {
                                            rtlMarker + cleanedTexts[index]
                                        }
                                    }
                                } else {
                                    // حالة احتياطية (Fallback): إذا اختلف عدد الأجزاء، نترجم كل كتلة على حدة[span_5](start_span)[span_5](end_span)
                                    translateBlocksIndividually(validBlocks, rtlMarker)
                                }

                                // تنظيف البلوكات غير المقبولة وترتيب القائمة النهائية
                                page.blocks.filter { it !in validBlocks }.forEach { it.translation = "" }
                                page.blocks = page.blocks.filterNot { it.translation.isBlank() && it.text.isNotBlank() }.toMutableList()

                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
        }
    }

    // دالة احتياطية في حال فشل التجميع المتزامن للترجمة الفردية[span_6](start_span)[span_6](end_span)
    private suspend fun translateBlocksIndividually(blocks: List<TranslationBlock>, rtlMarker: String) {
        supervisorScope {
            blocks.forEach { block ->
                launch {
                    try {
                        var cleanText = block.text.trim()
                        val isVerticalText = abs(block.angle) in 65.0f..115.0f

                        if (isVerticalText) {
                            cleanText = cleanText.replace(Regex("[ー\u2014\u2015]"), "")
                            cleanText = cleanText.replace(Regex("\\s+"), "")
                        } else {
                            cleanText = cleanText.replace(Regex("\\s+"), " ")
                        }

                        if (cleanText.length < 2) {
                            block.translation = rtlMarker + cleanText
                        } else {
                            val result = translationSemaphore.withPermit {
                                translator.translate(cleanText).await()
                            }
                            block.translation = if (result.isNotBlank()) {
                                rtlMarker + result.trim()
                            } else {
                                rtlMarker + cleanText
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        block.translation = block.text
                    }
                }
            }
        }
    }

    override fun close() {
        translator.close()
    }
}
