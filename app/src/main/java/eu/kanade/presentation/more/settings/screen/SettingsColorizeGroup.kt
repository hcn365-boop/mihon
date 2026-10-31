package eu.kanade.presentation.more.settings.screen

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.reader.colorize.ColorizerModelInstaller
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.util.collectAsState
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reader settings group for page colorization (Manga Light V6, runs on the device).
 * Texts are plain strings for now; move them to the i18n module later if needed.
 */
@Composable
fun getColorizeGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by readerPreferences.colorizeEnabled.collectAsState()
    var installed by remember { mutableStateOf(ColorizerModelInstaller.isInstalled(context)) }
    var status by remember { mutableStateOf("") }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                installModel(context, { status = it }) { ColorizerModelInstaller.import(context, uri) }
                installed = ColorizerModelInstaller.isInstalled(context)
            }
        }
    }

    return Preference.PreferenceGroup(
        title = "Colorization (Manga Light V6)",
        preferenceItems = listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = readerPreferences.colorizeEnabled,
                title = "Colorize pages",
                subtitle = "Runs on this device. Needs the model below.",
            ),
            Preference.PreferenceItem.SliderPreference(
                preference = readerPreferences.colorizeIntensity,
                valueRange = 0..120,
                title = "Color intensity",
                valueText = { "$it%" },
                visible = enabled,
            ),
            Preference.PreferenceItem.TextPreference(
                title = "Model",
                subtitle = when {
                    status.isNotEmpty() -> status
                    installed -> "Installed"
                    else -> "Not installed"
                },
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = readerPreferences.colorizeModelUrl,
                title = "Model link",
            ),
            Preference.PreferenceItem.TextPreference(
                title = "Download model",
                subtitle = "About 191 MB, from the link above. The model weights are CC BY-NC-SA 4.0: " +
                    "non-commercial use only, with attribution.",
                onClick = {
                    if (status.isEmpty()) {
                        scope.launch {
                            installModel(context, { status = it }) {
                                ColorizerModelInstaller.download(
                                    context,
                                    readerPreferences.colorizeModelUrl.get(),
                                ) { progress -> status = "Downloading ${(progress * 100).toInt()}%" }
                            }
                            installed = ColorizerModelInstaller.isInstalled(context)
                        }
                    }
                },
            ),
            Preference.PreferenceItem.TextPreference(
                title = "Import model file",
                subtitle = "Pick v6_generator.onnx from this phone",
                onClick = {
                    if (status.isEmpty()) importLauncher.launch(arrayOf("*/*"))
                },
            ),
        ),
    )
}

private suspend fun installModel(context: Context, onStatus: (String) -> Unit, block: suspend () -> Unit) {
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
