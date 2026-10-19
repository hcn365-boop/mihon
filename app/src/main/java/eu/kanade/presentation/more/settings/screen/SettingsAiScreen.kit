package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.reader.settings.AiSettingsContent
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.reader.colorize.AiPrefs
import mihon.app.di.appGraph
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.navigator.currentOrThrow

/**
 * The general settings of the AI features: colorize, upscale and translation. It shows the same
 * settings as the AI tab of the reader, but always the general ones (never one manga's own).
 */
object SettingsAiScreen : Screen() {

    @Composable
    override fun Content() {
        val backPress = LocalBackPress.currentOrThrow
        val context = LocalContext.current
        val prefs = remember { AiPrefs(context.appGraph.readerPreferences, null) }
        val topBarState = rememberTopAppBarState()

        Scaffold(
            topBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(topBarState),
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(ATMR.strings.pref_category_translations),
                    navigateUp = backPress::invoke,
                    scrollBehavior = scrollBehavior,
                )
            },
            content = { contentPadding ->
                Column(
                    modifier = Modifier
                        .padding(contentPadding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    AiSettingsContent(prefs)
                }
            },
        )
    }
}
