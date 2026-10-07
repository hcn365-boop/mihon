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
import eu.kanade.tachiyomi.ui.reader.colorize.ColorizerModelInstaller
import eu.kanade.tachiyomi.ui.reader.colorize.ModelKind
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.Preference
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.util.collectAsState
import kotlin.coroutines.cancellation.CancellationException

/** AI tab of the reader settings. Texts are plain strings for now. */
@Composable
internal fun ColumnScope.AiPage(viewModel: ReaderSettingsViewModel) {
    val prefs = viewModel.preferences
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var colorizerInstalled by remember { mutableStateOf(ColorizerModelInstaller.isInstalled(context)) }
    var upscalerInstalled by remember {
        mutableStateOf(ColorizerModelInstaller.isInstalled(context, ModelKind.UPSCALER))
    }
    var importKind by remember { mutableStateOf(ModelKind.COLORIZER) }

    fun refresh() {
        colorizerInstalled = ColorizerModelInstaller.isInstalled(context)
        upscalerInstalled = ColorizerModelInstaller.isInstalled(context, ModelKind.UPSCALER)
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
        val url = if (kind == ModelKind.COLORIZER) prefs.colorizeModelUrl.get() else prefs.upscaleModelUrl.get()
        scope.launch {
            runInstall(context, { status = it }) {
                ColorizerModelInstaller.downloadModel(context, kind, url) { progress ->
                    status = "Downloading ${(progress * 100).toInt()}%"
                }
            }
            refresh()
        }
    }

    // region Features

    val colorizeEnabled by prefs.colorizeEnabled.collectAsState()
    CheckboxItem(label = "Colorize pages (Manga Light V6)", pref = prefs.colorizeEnabled)
    if (colorizeEnabled) {
        IntSlider("Color intensity", prefs.colorizeIntensity, 0..120) { "$it%" }
    }
    CheckboxItem(label = "Upscale pages (Anime4K)", pref = prefs.upscaleEnabled)

    // endregion

    // region Models

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
    ModelRow(
        title = "Upscaler model",
        installed = upscalerInstalled,
        status = status,
        onDownload = { download(ModelKind.UPSCALER) },
        onImport = {
            importKind = ModelKind.UPSCALER
            importLauncher.launch(arrayOf("*/*"))
        },
    )
    UrlField("Upscaler model link", prefs.upscaleModelUrl)

    // endregion

    // region Limits

    IntSlider("Colorize: largest page", prefs.colorizeMaxMegapixels, 1..40) { "$it MP" }
    IntSlider("Upscale: largest page", prefs.upscaleMaxMegapixels, 1..12) { "$it MP" }
    ScaledSlider("Memory allowed per page (estimate)", prefs.aiMemoryBudgetMb, 128, 2..48)
    ScaledSlider("Processed pages cache", prefs.aiCacheSizeMb, 256, 1..32)

    // endregion

    // region Preload

    IntSlider("Pages downloaded ahead", prefs.pagePreloadCount, 1..16) { "$it" }
    IntSlider("Pages colorized ahead", prefs.colorizePreloadCount, 0..10) { "$it" }
    IntSlider("Pages upscaled ahead", prefs.upscalePreloadCount, 0..10) { "$it" }
    Text(
        text = "Colorized and upscaled counts are used by the background preload, which is added separately.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )

    // endregion
}

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
    SliderItem(
        label = label,
        value = (value / unit).coerceIn(range),
        valueRange = range,
        valueString = "${(value / unit).coerceIn(range) * unit} MB",
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
