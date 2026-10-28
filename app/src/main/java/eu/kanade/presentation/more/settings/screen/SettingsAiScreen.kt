package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.reader.settings.ColorizeSettings
import eu.kanade.presentation.reader.settings.PerformanceSettings
import eu.kanade.presentation.reader.settings.TranslationSection
import eu.kanade.presentation.reader.settings.UpscaleSettings
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.reader.colorize.AiPrefs
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The general settings of the AI features, one tab for each: colorize, upscale, translation, and the
 * settings of the phone (memory, cache, hardware). They are the same general values the reader tabs
 * edit, and this place also works when a value is so high that the reader cannot be used.
 */
object SettingsAiScreen : Screen() {

    private val tabs = listOf("Colorize", "Upscale", "Translate", "Performance")

    @Composable
    override fun Content() {
        val backPress = LocalBackPress.currentOrThrow
        val context = LocalContext.current
        val prefs = remember { AiPrefs(context.appGraph.readerPreferences, null) }
        val topBarState = rememberTopAppBarState()
        var selected by remember { mutableIntStateOf(0) }
        var confirmReset by remember { mutableStateOf(false) }

        Scaffold(
            topBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(topBarState),
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_category_ai),
                    navigateUp = backPress::invoke,
                    scrollBehavior = scrollBehavior,
                )
            },
            content = { contentPadding ->
                Column(modifier = Modifier.padding(contentPadding)) {
                    ScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
                        tabs.forEachIndexed { index, title ->
                            Tab(selected = selected == index, onClick = { selected = index }, text = { Text(title) })
                        }
                    }
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        when (selected) {
                            0 -> ColorizeSettings(prefs)
                            1 -> UpscaleSettings(prefs)
                            2 -> TranslationSection(prefs, inReader = false)
                            else -> PerformanceSettings(prefs)
                        }
                        TextButton(
                            onClick = { confirmReset = true },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) {
                            Text("Reset AI settings to defaults")
                        }
                    }
                }

                if (confirmReset) {
                    AlertDialog(
                        onDismissRequest = { confirmReset = false },
                        title = { Text("Reset AI settings?") },
                        text = {
                            Text(
                                "Colorize, upscale and translation go back to their default (off) values, " +
                                    "and the limits go back to safe values. API keys, server addresses and " +
                                    "model links are kept. Settings that belong to one manga are not changed.",
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    prefs.resetToDefaults()
                                    confirmReset = false
                                },
                            ) { Text("Reset") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
                        },
                    )
                }
            },
        )
    }
}
