package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.sublunar.amp.App
import com.sublunar.amp.BuildConfig
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.n
import com.sublunar.amp.ui.nSp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen

/** The bottom-nav "···" hub: secondary destinations that don't get their own tab. */
class MoreScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val active by App.settings.activeSource.collectAsState(initial = null)
        val source by App.source.collectAsState()
        // Each of these appears only when the library has something to put in it:
        // a server that doesn't tag composers shouldn't offer a Composers page.
        val genres by App.library.genres.collectAsState()
        val composers by App.library.composers.collectAsState()
        val compilations by App.library.compilations.collectAsState()

        // Keeps the tab bar and the now-playing button: More is part of the
        // library, not a departure from it. Settings, below, is the departure —
        // it drops both, so there is a clear edge to the app's own preferences.
        LibrarySubPage(moreActive = true) {
            // No back button: More is one of the tabs, and the bar below is how
            // you leave it — a chevron here would suggest a page above it.
            AppHeader(
                title = "More",
                // Settings takes the corner square rather than a row of its own:
                // it is the app's own preferences, not another place in the
                // library, and the gear is the one control here that says so.
                leftAction = HeaderAction(AppIcons.Settings) { go { SettingsScreen(it) } },
                searchAction = { openLibrarySearch(withKeyboard = true) },
                rightAction = HeaderAction(AppIcons.Waveform) { go { NowPlayingScreen(it) } },
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // Where the music comes from, named by whichever source is in
                // use — with one configured it reads as a label, with several it
                // is the switch.
                item {
                    TextRow(
                        title = "Sources",
                        subtitle = when {
                            sources.size > 1 -> "${active?.name.orEmpty()} · ${sources.size} sources"
                            else -> active?.name
                        },
                        onClick = { go { SourcesScreen(it) } },
                    )
                }
                if (genres.isNotEmpty()) {
                    item {
                        TextRow(title = "Genres") { go { GenresScreen(it) } }
                    }
                }
                if (compilations.isNotEmpty()) {
                    item {
                        TextRow(title = "Compilations") { go { CompilationsScreen(it) } }
                    }
                }
                if (composers.isNotEmpty()) {
                    item {
                        TextRow(title = "Composers") { go { ComposersScreen(it) } }
                    }
                }
                // Nothing to download when the audio is already on the phone —
                // see MusicSource.supportsDownloads.
                if (source.supportsDownloads) {
                    item { TextRow(title = "Downloaded Songs") { go { DownloadsScreen(it) } } }
                }
            }
        }
    }

}

class AboutScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        ListScreen(onBack = { goBack() }, title = "About") {
            Column(
                modifier = Modifier.fillMaxSize().padding(n(24)),
                verticalArrangement = Arrangement.spacedBy(n(10)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppText("amp", nSp(26))
                AppText("Version ${BuildConfig.VERSION_NAME}", nSp(14), dim = true)
                AppText(
                    "(A)nother (M)usic (P)layer — for the Light Phone III. Streams " +
                        "and downloads from your own Navidrome, Subsonic, Plex or " +
                        "Bandcamp library, and plays files kept on the phone.",
                    nSp(15),
                    lineHeight = nSp(21),
                    dim = true,
                    align = TextAlign.Center,
                )
            }
        }
    }
}
