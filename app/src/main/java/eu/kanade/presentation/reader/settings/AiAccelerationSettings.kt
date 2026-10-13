package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.colorize.Acceleration
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.util.collectAsState

/**
 * Where the colorizer and the upscaler run. NNAPI may use the GPU or NPU, or may be slower or give
 * different colors on some phones, so each model has its own switch. A change applies to the next
 * page that is processed.
 */
@Composable
internal fun AccelerationSection(prefs: ReaderPreferences) {
    Text(
        text = "Hardware for AI models. NNAPI can use the GPU or NPU if the phone supports it; if it fails, " +
            "the app switches back to the CPU until it is restarted.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    AccelerationRow("Colorizer runs on", prefs.colorizeAcceleration)
    AccelerationRow("Upscaler runs on", prefs.upscaleAcceleration)

    val threads by prefs.aiThreads.collectAsState()
    SliderItem(
        label = "CPU threads for AI models (0 = automatic)",
        value = threads,
        valueRange = 0..8,
        valueString = if (threads == 0) "Auto" else "$threads",
        onChange = { prefs.aiThreads.set(it) },
    )
}

@Composable
private fun AccelerationRow(label: String, pref: Preference<String>) {
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
            TextButton(onClick = { open = true }) { Text(Acceleration.fromName(current).name) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Acceleration.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.name) },
                        onClick = {
                            pref.set(option.name)
                            open = false
                        },
                    )
                }
            }
        }
    }
}
