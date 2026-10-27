package eu.kanade.presentation.reader.settings

import android.widget.Toast
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.colorize.AiPrefs
import eu.kanade.tachiyomi.ui.reader.colorize.Anime4kModels
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.Preference
import tachiyomi.presentation.core.util.collectAsState
import kotlin.coroutines.cancellation.CancellationException

private const val ENGINE_ACNET = "ACNET"
private const val ENGINE_MODELS = "ANIME4K_ONNX"

/**
 * Which upscaler runs: the built-in ACNet, or the Anime4K models (a restore model first, then an
 * upscale model). A model that is not on the phone yet is downloaded as soon as it is picked.
 */
@Composable
internal fun UpscalerModelsSection(prefs: AiPrefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine by prefs.upscaleEngine.collectAsState()
    val restore by prefs.upscaleRestoreModel.collectAsState()
    val main by prefs.upscaleMainModel.collectAsState()
    var status by remember { mutableStateOf("") }
    // Changes after a download, so the "installed" marks are drawn again.
    var refresh by remember { mutableIntStateOf(0) }

    fun pick(pref: Preference<String>, name: String) {
        pref.set(name)
        if (name.isEmpty() || Anime4kModels.isInstalled(context, name)) return
        scope.launch {
            status = "Downloading $name..."
            try {
                Anime4kModels.download(context, name, prefs.upscaleModelsBaseUrl.get()) { progress ->
                    status = "Downloading $name ${(progress * 100).toInt()}%"
                }
                Toast.makeText(context, "$name installed", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Toast.makeText(context, e.message ?: "Download failed", Toast.LENGTH_LONG).show()
            }
            status = ""
            refresh++
        }
    }

    PickRow(
        label = "Upscaler",
        current = if (engine == ENGINE_MODELS) "Anime4K models" else "Anime4K ACNet (built in)",
        items = listOf(ENGINE_ACNET to "Anime4K ACNet (built in)", ENGINE_MODELS to "Anime4K models"),
        onPick = { prefs.upscaleEngine.set(it) },
    )
    if (engine != ENGINE_MODELS) return

    // `refresh` is read so the marks below are drawn again after a download.
    val installed = { name: String -> refresh >= 0 && Anime4kModels.isInstalled(context, name) }
    fun label(name: String, list: List<Anime4kModels.Entry>, none: String): String =
        list.firstOrNull { it.name == name }?.label ?: none

    PickRow(
        label = "1. Clean before upscaling",
        current = label(restore, Anime4kModels.restore, "None"),
        items = listOf("" to "None") +
            Anime4kModels.restore.map { it.name to (if (installed(it.name)) "✓ " else "") + it.label },
        onPick = { pick(prefs.upscaleRestoreModel, it) },
    )
    PickRow(
        label = "2. Upscale",
        current = label(main, Anime4kModels.upscale, "None"),
        items = listOf("" to "None") +
            Anime4kModels.upscale.map { it.name to (if (installed(it.name)) "✓ " else "") + it.label },
        onPick = { pick(prefs.upscaleMainModel, it) },
    )

    val scale = Anime4kModels.scaleOf(restore) * Anime4kModels.scaleOf(main)
    Text(
        text = if (status.isNotEmpty()) status else "The page becomes ×$scale larger. A tick marks models already on the phone.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )

    val baseUrl by prefs.upscaleModelsBaseUrl.collectAsState()
    OutlinedTextField(
        value = baseUrl,
        onValueChange = { prefs.upscaleModelsBaseUrl.set(it) },
        label = { Text("Models folder link") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** A line with a title and a button that opens a list of (key, text) choices. */
@Composable
private fun PickRow(label: String, current: String, items: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) { Text(current) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                items.forEach { (key, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onPick(key)
                            open = false
                        },
                    )
                }
            }
        }
    }
}
