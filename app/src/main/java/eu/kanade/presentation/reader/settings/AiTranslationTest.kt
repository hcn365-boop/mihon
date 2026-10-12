package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.colorize.AiConfig
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Translates one short sentence with the engine and settings currently chosen, so a wrong key,
 * model name or server address shows up here instead of silently on a page.
 */
@Composable
internal fun TranslationTestButton() {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    TextButton(
        enabled = !running,
        modifier = Modifier.padding(horizontal = 8.dp),
        onClick = {
            val settings = AiConfig.translation?.settings
            if (settings == null) {
                result = "Translation is turned off."
                return@TextButton
            }
            running = true
            scope.launch {
                result = withContext(Dispatchers.IO) {
                    try {
                        val page = PageTranslation(imgWidth = 100f, imgHeight = 100f)
                        page.blocks = mutableListOf(
                            TranslationBlock(
                                text = sampleText(settings.from),
                                width = 50f,
                                height = 20f,
                                symWidth = 10f,
                                symHeight = 10f,
                                angle = 0f,
                                x = 0f,
                                y = 0f,
                            ),
                        )
                        settings.engine.build(settings).use { it.translate(mutableMapOf("test" to page)) }
                        val translated = page.blocks.first().translation
                        "${settings.engine.label}\n\n${translated.ifBlank { "(empty result)" }}"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        "Error: ${e.message ?: e::class.java.simpleName}"
                    }
                }
                running = false
            }
        },
    ) {
        Text(if (running) "Testing..." else "Test translation engine")
    }

    result?.let { text ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text("Engine test") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { result = null }) { Text("OK") } },
        )
    }
}

private fun sampleText(language: TextRecognizerLanguage): String = when (language) {
    TextRecognizerLanguage.JAPANESE -> "こんにちは、お元気ですか？"
    TextRecognizerLanguage.CHINESE -> "你好，你好吗？"
    TextRecognizerLanguage.KOREAN -> "안녕하세요, 잘 지내세요?"
    else -> "Hello, how are you?"
}
