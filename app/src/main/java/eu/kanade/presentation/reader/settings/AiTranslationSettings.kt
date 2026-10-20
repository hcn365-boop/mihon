package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.translation.OcrMode
import eu.kanade.translation.TranslationFont
import eu.kanade.translation.TranslationStore
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TextTranslators
import tachiyomi.core.common.preference.Preference
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.util.collectAsState
import eu.kanade.translation.MangaTranslationContext
import eu.kanade.tachiyomi.ui.reader.colorize.AiPrefs


/** Translation part of the AI tab. Texts are plain strings for now. */
@Composable
internal fun TranslationSection(prefs: AiPrefs) {
    val context = LocalContext.current
    val enabled by prefs.translateEnabled.collectAsState()
    CheckboxItem(label = "Translate pages", pref = prefs.translateEnabled)
    if (!enabled) return

    val readable = { name: String -> name.lowercase().replaceFirstChar { it.uppercase() } }
    ChoiceRow(
        label = "Language of the page",
        pref = prefs.translateFrom,
        options = TextRecognizerLanguage.entries.map { it.name to readable(it.name) },
    )
        CheckboxItem(label = "Choose the page language automatically for each manga", pref = prefs.translateAutoLanguage)
    val autoLanguage by prefs.translateAutoLanguage.collectAsState()
    if (autoLanguage) {
        CheckboxItem(label = "Read it from the title tags, like (Japanese)", pref = prefs.translateAutoFromTitle)
        CheckboxItem(label = "Use the language of the source", pref = prefs.translateAutoFromSource)
    }
    CheckboxItem(label = "Check each page's language after reading it (ML Kit)", pref = prefs.translateConfirmLanguage)
    ChoiceRow(
        label = "Translate to",
        pref = prefs.translateTo,
        options = TextTranslatorLanguage.entries.map { it.name to readable(it.name) },
    )
    ChoiceRow(
        label = "Translation engine",
        pref = prefs.translationEngine,
        options = TextTranslators.entries.map { it.name to it.label },
    )
    ChoiceRow(
        label = "Text recognition (OCR)",
        pref = prefs.translationOcrMode,
        options = OcrMode.entries.map { it.name to readable(it.name) },
    )
    ChoiceRow(
        label = "Font",
        pref = prefs.translationFont,
        options = TranslationFont.entries.map { it.name to it.label },
    )

        val engineName by prefs.translationEngine.collectAsState()
    val engine = TextTranslators.fromName(engineName)
    if (engine == TextTranslators.GEMINI || engine == TextTranslators.OPENROUTER) {
        TextSetting("Model name", prefs.translationModel)
        TextSetting("API key", prefs.translationApiKey, secret = true)
        TextSetting("Temperature", prefs.translationTemperature)
        TextSetting("Max output tokens", prefs.translationMaxTokens)
    }
    if (engine == TextTranslators.LIBRETRANSLATE || engine == TextTranslators.LMSTUDIO) {
        TextSetting("Server address", prefs.translationServerUrl)
    }

    TSlider("Bubble expansion & padding", prefs.translateCollisionPadding, 100..200) { "$it%" }

    TSlider("Pages per request", prefs.translatePagesPerRequest, 1..10) { "$it" }
    ScaledChars("Max characters per request", prefs.translateMaxChars)
    TSlider("Wait before sending a partial request", prefs.translateMaxWaitSeconds, 1..30) { "$it s" }
    TSlider("Pages read ahead for translation", prefs.translatePreloadCount, 0..10) { "$it" }

    TranslationTestButton()
    
    var size by remember { mutableStateOf(TranslationStore.sizeBytes(context)) }
    TextButton(
        onClick = {
            TranslationStore.clear(context)
            size = TranslationStore.sizeBytes(context)
        },
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        Text("Delete saved translations (${size / 1024} KB)")
    }
}

@Composable
private fun TSlider(label: String, pref: Preference<Int>, range: IntRange, text: (Int) -> String) {
    val value by pref.collectAsState()
    SliderItem(
        label = label,
        value = value,
        valueRange = range,
        valueString = text(value),
        onChange = { pref.set(it) },
    )
}

/** Characters per request, in steps of 100. */
@Composable
private fun ScaledChars(label: String, pref: Preference<Int>) {
    val value by pref.collectAsState()
    val steps = (value / 100).coerceIn(5..80)
    SliderItem(
        label = label,
        value = steps,
        valueRange = 5..80,
        valueString = "${steps * 100}",
        onChange = { pref.set(it * 100) },
    )
}


@Composable
private fun TextSetting(label: String, pref: Preference<String>, secret: Boolean = false) {
    val value by pref.collectAsState()
    OutlinedTextField(
        value = value,
        onValueChange = { pref.set(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** A setting stored as a name, chosen from a drop-down list of (name, label) options. */
@Composable
private fun ChoiceRow(label: String, pref: Preference<String>, options: List<Pair<String, String>>) {
    val current by pref.collectAsState()
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) {
                Text(options.firstOrNull { it.first.equals(current, ignoreCase = true) }?.second ?: current)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (name, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            pref.set(name)
                            open = false
                        },
                    )
                }
            }
        }
    }
}
