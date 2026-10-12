package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import tachiyomi.core.common.util.system.logcat
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@Suppress("PropertyName", "MaxLineLength", "FunctionName")
class GoogleTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
) : TextTranslator {
    private val client1 = "gtx"
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val urlPattern = Regex("(?i)(https?://\\S+|www\\.\\S+|\\S+\\.(com|net|org|io|me|cc|tv|info))")
    private val symbolsFilterPattern = Regex("""[_~•°^@#$&|\\-]""")
    private val SAFE_SEPARATOR = " . "

    // يكتشف الكلمات اللاتينية/الإنجليزية المتبقية في النص المترجم
    private val untranslatedWordPattern = Regex("""[a-zA-Z]{2,}""")

    // لغات لا تستخدم الأحرف اللاتينية — نُفحّص فيها الكلمات غير المترجمة
    private val nonLatinLanguages = setOf(
        TextTranslatorLanguage.ARABIC,
        TextTranslatorLanguage.JAPANESE,
        TextTranslatorLanguage.CHINESESIM,
        TextTranslatorLanguage.CHINESETRAD,
        TextTranslatorLanguage.KOREAN,
        TextTranslatorLanguage.RUSSIAN,
        TextTranslatorLanguage.GREEK_MODERN,
        TextTranslatorLanguage.HEBREW,
        TextTranslatorLanguage.HINDI,
        TextTranslatorLanguage.THAI,
        TextTranslatorLanguage.PERSIAN,
        TextTranslatorLanguage.URDU,
        TextTranslatorLanguage.BENGALI,
        TextTranslatorLanguage.TAMIL,
        TextTranslatorLanguage.TELUGU,
        TextTranslatorLanguage.MALAYALAM,
        TextTranslatorLanguage.KANNADA,
        TextTranslatorLanguage.GUJARATI,
        TextTranslatorLanguage.PANJABI_PUNJABI,
        TextTranslatorLanguage.MARATHI,
        TextTranslatorLanguage.ARMENIAN,
        TextTranslatorLanguage.GEORGIAN,
        TextTranslatorLanguage.UKRAINIAN,
        TextTranslatorLanguage.BELARUSIAN,
        TextTranslatorLanguage.BULGARIAN,
        TextTranslatorLanguage.SERBIAN,
        TextTranslatorLanguage.MACEDONIAN,
        TextTranslatorLanguage.MONGOLIAN,
        TextTranslatorLanguage.KAZAKH,
        TextTranslatorLanguage.KIRGHIZ_KYRGYZ,
        TextTranslatorLanguage.TAJIK,
        TextTranslatorLanguage.TIBETAN,
        TextTranslatorLanguage.LAO,
        TextTranslatorLanguage.CENTRAL_KHMER,
        TextTranslatorLanguage.BURMESE,
        TextTranslatorLanguage.SINHALA_SINHALESE,
        TextTranslatorLanguage.AMHARIC,
        TextTranslatorLanguage.KURDISH,
        TextTranslatorLanguage.PUSHTO_PASHTO,
        TextTranslatorLanguage.UIGHUR_UYGHUR,
        TextTranslatorLanguage.SINDHI,
        TextTranslatorLanguage.YIDDISH,
        TextTranslatorLanguage.DIVEHI_DHIVEHI_MALDIVIAN,
        TextTranslatorLanguage.NEPALI,
        TextTranslatorLanguage.DZONGKHA,
    )

    // هل لغة الترجمة الحالية لا تستخدم لاتيني؟
    private val shouldFixUntranslated = nonLatinLanguages.contains(toLang)

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) = coroutineScope {
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

        // 1. تجميع البلوكات الصالحة
        val allValidBlocks = mutableListOf<TranslationBlock>()
        pages.forEach { (_, page) ->
            page.blocks.forEach { block ->
                val isAcceptedAngle = (block.angle >= -15.0f && block.angle <= 15.0f) ||
                    (block.angle >= 75.0f && block.angle <= 105.0f) ||
                    (block.angle <= -75.0f && block.angle >= -105.0f)
                val isUrl = urlPattern.containsMatchIn(block.text)

                if (isAcceptedAngle && !isUrl) {
                    allValidBlocks.add(block)
                } else {
                    block.translation = ""
                }
            }
        }

        if (allValidBlocks.isEmpty()) return@coroutineScope

        // 2. التحزيم
        val batches = mutableListOf<MutableList<TranslationBlock>>()
        var currentBatch = mutableListOf<TranslationBlock>()
        var currentLength = 0

        for (block in allValidBlocks) {
            val cleanedBlockText = cleanSymbols(block.text)
            val textToAdd = cleanedBlockText + SAFE_SEPARATOR

            if (currentLength + textToAdd.length > 1700 && currentBatch.isNotEmpty()) {
                batches.add(currentBatch)
                currentBatch = mutableListOf()
                currentLength = 0
            }
            currentBatch.add(block)
            currentLength += textToAdd.length + 1
        }
        if (currentBatch.isNotEmpty()) batches.add(currentBatch)

        // 3. الترجمة وإصلاح الكلمات بالتوازي (Parallel Processing)
        val deferredBatches = batches.map { batch ->
            async {
                val mergedText = batch.joinToString("\n") {
                    cleanSymbols(it.text) + SAFE_SEPARATOR
                }

                val translatedMergedText = try {
                    translateText(toLang.code, mergedText)
                } catch (e: Exception) {
                    logcat { "Translation failed: ${e.message}" }
                    ""
                }

                if (translatedMergedText.isNotBlank()) {
                    val translatedLines = translatedMergedText.split("\n")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }

                    val textToFixMap = mutableMapOf<Int, String>()

                    batch.forEachIndexed { index, _ ->
                        if (index < translatedLines.size) {
                            val cleanText = translatedLines[index]
                                .removeSuffix(".")
                                .removeSuffix(" .")
                                .trim()

                            textToFixMap[index] = cleanText
                        }
                    }

                    // إصلاح الكلمات غير المترجمة دفعة واحدة لهذه الحزمة
                    val fixedTexts = fixUntranslatedWordsBatch(textToFixMap.values.toList(), toLang.code)

                    batch.forEachIndexed { index, block ->
                        if (index < fixedTexts.size) {
                            block.translation = rtlMarker + fixedTexts[index]
                        }
                    }
                }
            }
        }

        // انتظار انتهاء جميع الحزم
        deferredBatches.awaitAll()
    }

    /**
     * يقوم بجمع كل الكلمات غير المترجمة من جميع السطور، وترسلها في طلب شبكة واحد فقط
     */
    private suspend fun fixUntranslatedWordsBatch(translatedTexts: List<String>, langCode: String): List<String> {
        if (!shouldFixUntranslated) return translatedTexts

        // استخراج كل الكلمات اللاتينية من جميع النصوص معاً بدون تكرار
        val allUntranslatedWords = translatedTexts.flatMap { text ->
            untranslatedWordPattern.findAll(text).map { it.value }.toList()
        }.distinct()

        if (allUntranslatedWords.isEmpty()) return translatedTexts

        // تجميع الكلمات في نص واحد يفصل بينها سطر جديد
        val wordsToTranslateMerged = allUntranslatedWords.joinToString("\n")

        val translatedWordsMerged = try {
            translateText(langCode, wordsToTranslateMerged)
        } catch (e: Exception) {
            logcat { "Failed to translate missing words batch: ${e.message}" }
            ""
        }

        val translationMap = mutableMapOf<String, String>()
        if (translatedWordsMerged.isNotBlank()) {
            val translatedWordsList = translatedWordsMerged.split("\n").map { it.trim() }
            allUntranslatedWords.forEachIndexed { index, word ->
                if (index < translatedWordsList.size) {
                    val singleTranslation = translatedWordsList[index].removeSuffix(".").removeSuffix(" .").trim()
                    if (singleTranslation.isNotBlank() && !untranslatedWordPattern.containsMatchIn(singleTranslation)) {
                        translationMap[word] = singleTranslation
                    }
                }
            }
        }

        // استبدال الكلمات في النصوص الأصلية
        return translatedTexts.map { originalText ->
            var fixedText = originalText
            translationMap.forEach { (englishWord, translatedWord) ->
                // استخدام Regex بحدود الكلمة (\b) لتجنب استبدال أجزاء من كلمات أخرى
                fixedText = fixedText.replace(Regex("""\b$englishWord\b"""), translatedWord)
            }
            fixedText
        }
    }

    private fun cleanSymbols(rawText: String): String {
        return rawText.replace("\n", " ")
            .replace(symbolsFilterPattern, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private suspend fun translateText(lang: String, text: String): String {
        val access = getTranslateUrl(lang, text)
        val request: Request = Request.Builder()
            .url(access)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            )
            .build()

        val response = okHttpClient.newCall(request).await()
        val responseBody = response.body?.string() ?: return ""

        return try {
            val rootArray = JSONArray(responseBody)
            val sentencesArray = rootArray.getJSONArray(0)
            val result = StringBuilder()

            for (i in 0 until sentencesArray.length()) {
                val sentence = sentencesArray.getJSONArray(i)
                if (!sentence.isNull(0)) {
                    result.append(sentence.getString(0))
                }
            }
            result.toString()
        } catch (e: Exception) {
            logcat { "Parsing Error: $e" }
            ""
        }
    }

    private fun getTranslateUrl(lang: String, text: String): String {
        val encode: String = URLEncoder.encode(text, "utf-8")
        val tk = calculateToken(text)

        return "https://translate.google.com/translate_a/single" +
            "?client=$client1" +
            "&sl=auto" +
            "&tl=$lang" +
            "&tk=$tk" +
            "&q=$encode" +
            "&dt=t" +
            "&ie=UTF-8" +
            "&oe=UTF-8"
    }

    private fun calculateToken(str: String): String {
        var j: Long = 406644
        val list = mutableListOf<Int>()
        var i = 0
        while (i < str.length) {
            val codePoint = str.codePointAt(i)
            if (codePoint < 128) {
                list.add(codePoint)
            } else if (codePoint < 2048) {
                list.add((codePoint shr 6) or 192)
                list.add((codePoint and 63) or 128)
            } else {
                list.add((codePoint shr 12) or 224)
                list.add(((codePoint shr 6) and 63) or 128)
                list.add((codePoint and 63) or 128)
            }
            i += if (Character.isSupplementaryCodePoint(codePoint)) 2 else 1
        }
        for (num in list) j = RL(j + num.toLong(), "+-a^+6")
        var rL = RL(j, "+-3^+b+-f") xor 3293161072L
        if (rL < 0) rL = (rL and 2147483647L) + 2147483648L
        val res = (rL % 1000000L)
        return "$res.${406644L xor res}"
    }

    private fun RL(j: Long, str: String): Long {
        var res = j
        var i = 0
        while (i < str.length - 2) {
            val shift = if (str[i + 2] in 'a'..'z') str[i + 2].code - 'W'.code else str[i + 2].digitToInt()
            val shiftValue = if (str[i + 1] == '+') res ushr shift else res shl shift
            res = if (str[i] == '+') (res + shiftValue) and 4294967295L else res xor shiftValue
            i += 3
        }
        return res
    }

    override fun close() {}
}
