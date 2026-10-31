package eu.kanade.presentation.reader.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.colorize.AiPrefs
import eu.kanade.tachiyomi.ui.reader.colorize.ColorizerModelInstaller
import eu.kanade.tachiyomi.ui.reader.colorize.ModelKind
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.translation.MangaTranslationContext
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.Preference
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.util.collectAsState
import kotlin.coroutines.cancellation.CancellationException

// region Pages of the reader settings (one tab each)

@Composable
internal fun ColumnScope.ColorizePage(viewModel: ReaderSettingsViewModel) {
    ColorizeSettings(MangaScope(viewModel.preferences))
}

@Composable
internal fun ColumnScope.UpscalePage(viewModel: ReaderSettingsViewModel) {
    UpscaleSettings(MangaScope(viewModel.preferences))
}

@Composable
internal fun ColumnScope.TranslatePage(viewModel: ReaderSettingsViewModel) {
    TranslationSection(MangaScope(viewModel.preferences), inReader = true)
}

/**
 * The "separate settings for this manga" box, shown at the top of each AI tab of the reader.
 * @return the settings to show: the manga's own when the box is checked, the general ones otherwise
 */
@Composable
internal fun MangaScope(base: ReaderPreferences): AiPrefs {
    val mangaId = MangaTranslationContext.mangaId
    val ownPref = remember(mangaId) { if (mangaId >= 0) base.mangaOverride(mangaId) else null }
    val ownOn = ownPref?.collectAsState()?.value == true
    val prefs = remember(mangaId, ownOn) { AiPrefs(base, if (ownOn) mangaId else null) }

    if (ownPref != null) {
        CheckboxItem(
            label = "Use separate settings for this manga",
            checked = ownOn,
            onClick = {
                // Its own settings start as a copy of the general ones.
                if (!ownOn) AiPrefs(base, mangaId).copyGeneralSettings()
                ownPref.set(!ownOn)
            },
        )
    }
    return prefs
}

// endregion

// region Settings, shared by the reader tabs and the app's settings screen

@Composable
internal fun ColorizeSettings(prefs: AiPrefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by prefs.colorizeEnabled.collectAsState()
    var status by remember { mutableStateOf("") }
    var colorizerInstalled by remember { mutableStateOf(ColorizerModelInstaller.isInstalled(context)) }
    var samInstalled by remember { mutableStateOf(ColorizerModelInstaller.isInstalled(context, ModelKind.COLORIZER_SAM)) } // SAM
    var importKind by remember { mutableStateOf(ModelKind.COLORIZER) }

    fun refresh() {
        colorizerInstalled = ColorizerModelInstaller.isInstalled(context)
        samInstalled = ColorizerModelInstaller.isInstalled(context, ModelKind.COLORIZER_SAM) // SAM
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runInstall(context, { status = it }) { ColorizerModelInstaller.importModel(context, importKind, uri) }
                refresh()
            }
        }
    }

    fun download(kind: ModelKind) {
        if (status.isNotEmpty()) return
        val url = when (kind) {
            ModelKind.COLORIZER -> prefs.colorizeModelUrl.get()
            ModelKind.COLORIZER_SAM -> prefs.colorizeSamModelUrl.get() // SAM
        }
        scope.launch {
            runInstall(context, { status = it }) {
                ColorizerModelInstaller.downloadModel(context, kind, url) { progress ->
                    status = "Downloading ${(progress * 100).toInt()}%"
                }
            }
            refresh()
        }
    }

    CheckboxItem(label = "Colorize pages (Manga Light V6)", pref = prefs.colorizeEnabled)
    if (enabled) {
        IntSlider("Color intensity", prefs.colorizeIntensity, 0..120) { "$it%" }
    }
    
    // تم تحديث التسمية لتوضيح الاعتماد على ملف الميزات الثنائي sam_features.bin
    CheckboxItem(label = "Use the SAM features guide (needs sam_features.bin)", pref = prefs.colorizeUseSam) // SAM

    ModelRow(
        title = "Colorizer model",
        installed = colorizerInstalled,
        status = status,
        onDownload = { download(ModelKind.COLORIZER) },
        onImport = {
            importKind = ModelKind.COLORIZER
            importLauncher.launch(arrayOf("*/*"))
        },
    )
    UrlField("Colorizer model link", prefs.colorizeModelUrl)
    
    // تم تحديث مسمى خانة النموذج الاختياري إلى ملف ميزات الـ SAM
    ModelRow( // SAM
        title = "SAM features file (optional)", // SAM
        installed = samInstalled, // SAM
        status = status, // SAM
        onDownload = { download(ModelKind.COLORIZER_SAM) }, // SAM
        onImport = { // SAM
            importKind = ModelKind.COLORIZER_SAM // SAM
            importLauncher.launch(arrayOf("*/*")) // SAM
        }, // SAM
    ) // SAM
    UrlField("SAM features link", prefs.colorizeSamModelUrl) // SAM

    IntSlider("Colorize: largest page", prefs.colorizeMaxMegapixels, 1..40) { "$it MP" }
    IntSlider("Pages colorized ahead", prefs.colorizePreloadCount, 0..10) { "$it" }
}

@Composable
internal fun UpscaleSettings(prefs: AiPrefs) {
    val normalize by prefs.upscaleNormalizeEnabled.collectAsState()
    val finalPass by prefs.upscaleFinalPassEnabled.collectAsState()

    CheckboxItem(label = "Upscale pages", pref = prefs.upscaleEnabled)
    Text(
        text = "Steps run in this order: 1 shrink, 2 clean, 3 upscale, 4 final pass.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )

    CheckboxItem(label = "1. Shrink big pages first", pref = prefs.upscaleNormalizeEnabled)
    if (normalize) {
        IntSlider("Shrink to short side", prefs.upscaleNormalizeShortSide, 600..1600) { "$it px" }
    }

    UpscalerModelsSection(prefs) // steps 2 and 3

    CheckboxItem(label = "4. Final pass with Anime4K ACNet", pref = prefs.upscaleFinalPassEnabled)
    if (finalPass) {
        CheckboxItem(label = "Keep the page size after the final pass", pref = prefs.upscaleFinalPassKeepSize)
        IntSlider("Final pass: largest page", prefs.upscaleFinalPassMaxMegapixels, 1..12) { "$it MP" }
    }

    IntSlider("Upscale: largest page", prefs.upscaleMaxMegapixels, 1..12) { "$it MP" }
    IntSlider("Upscale: largest result", prefs.upscaleMaxOutputMegapixels, 2..40) { "$it MP" }
    IntSlider("Pages upscaled ahead", prefs.upscalePreloadCount, 0..10) { "$it" }
}

/** Settings of the phone, not of a manga: memory, cache, threads and hardware. Shown in the app settings. */
@Composable
internal fun PerformanceSettings(prefs: AiPrefs) {
    AccelerationSection(prefs)
    ScaledSlider("Memory allowed per page (estimate)", prefs.aiMemoryBudgetMb, 128, 2..48)
    ScaledSlider("Processed pages cache", prefs.aiCacheSizeMb, 256, 1..32)
    IntSlider("Pages downloaded ahead", prefs.pagePreloadCount, 1..16) { "$it" }
}

// endregion

@Composable
private fun IntSlider(label: String, pref: Preference<Int>, range: IntRange, text: (Int) -> String) {
    val value by pref.collectAsState()
    SliderItem(
        label = label,
        value = value,
        valueRange = range,
        valueString = text(value),
        onChange = { pref.set(it) },
    )
}

/** A slider over a value stored in MB, moving in steps of [unit] MB. */
@Composable
private fun ScaledSlider(label: String, pref: Preference<Int>, unit: Int, range: IntRange) {
    val value by pref.collectAsState()
    val steps = (value / unit).coerceIn(range)
    SliderItem(
        label = label,
        value = steps,
        valueRange = range,
        valueString = "${steps * unit} MB",
        onChange = { pref.set(it * unit) },
    )
}

@Composable
private fun UrlField(label: String, pref: Preference<String>) {
    val value by pref.collectAsState()
    OutlinedTextField(
        value = value,
        onValueChange = { pref.set(it) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun ModelRow(
    title: String,
    installed: Boolean,
    status: String,
    onDownload: () -> Unit,
    onImport: () -> Unit,
) {
    val state = when {
        status.isNotEmpty() -> status
        installed -> "Installed"
        else -> "Not installed"
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text = "$title: $state", style = MaterialTheme.typography.bodyMedium)
        Row {
            TextButton(onClick = onDownload) { Text("Download") }
            TextButton(onClick = onImport) { Text("Import file") }
        }
    }
}

private suspend fun runInstall(
    context: android.content.Context,
    onStatus: (String) -> Unit,
    block: suspend () -> Unit,
) {
    onStatus("Installing...")
    try {
        block()
        Toast.makeText(context, "Model installed", Toast.LENGTH_SHORT).show()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Toast.makeText(context, e.message ?: "Installation failed", Toast.LENGTH_LONG).show()
    } finally {
        onStatus("")
    }
}
