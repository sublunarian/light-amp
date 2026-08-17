package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import com.sublunar.amp.ui.PlayerTheme
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.n
import com.sublunar.amp.ui.nSp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import kotlinx.coroutines.launch

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

        // Under Inline Search, More is a destination in the bar and so keeps the
        // bar — a tab that hides the navigation the moment you reach it is the
        // one tab you cannot leave the way you arrived. In the classic layout it
        // is still the library page's own menu, opened from that page's header,
        // and it covers the bar as a menu should.
        val inline = App.inlineSearch.collectAsState().value
        val body: @Composable ColumnScope.() -> Unit = {
            AppHeader(
                title = "More",
                // As a destination in the bar, this corner is the player, the
                // same as on every tab — there is nothing to go "back" to when
                // you arrived sideways, and the bar itself is the way out.
                //
                // In the classic layout it is still a menu pushed from a page,
                // and it covers the bar: take the back button away there and the
                // page has no exit at all.
                leftAction = if (inline) {
                    HeaderAction(AppIcons.Waveform) { go { NowPlayingScreen(it) } }
                } else {
                    null
                },
                onBack = if (inline) null else ({ goBack() }),
                // No search here: this page is a short menu you read rather than
                // a list you look through, and search belongs to the library
                // pages it would send you back to anyway.
                rightAction = HeaderAction(AppIcons.Settings) { go { SettingsScreen(it) } },
            )
            ScrollableList(modifier = Modifier.fillMaxSize()) {
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
                // Every row below opens a library page in its own right rather
                // than a page of this one — More is a way in, like the tab bar,
                // not somewhere above them to go back up to. See openLibraryPage.
                //
                // Liked lives here rather than behind a heart in three different
                // headers: it is a view across the library, which is exactly
                // what this page collects.
                if (genres.isNotEmpty()) {
                    item {
                        TextRow(title = "Genres") { openLibraryPage { GenresScreen(it) } }
                    }
                }
                if (compilations.isNotEmpty()) {
                    item {
                        TextRow(title = "Compilations") {
                            openLibraryPage { CompilationsScreen(it) }
                        }
                    }
                }
                if (composers.isNotEmpty()) {
                    item {
                        TextRow(title = "Composers") { openLibraryPage { ComposersScreen(it) } }
                    }
                }
                // Nothing to download when the audio is already on the phone —
                // see MusicSource.supportsDownloads.
                if (source.supportsDownloads) {
                    item {
                        TextRow(title = "Downloaded Songs") {
                            openLibraryPage { DownloadsScreen(it) }
                        }
                    }
                }
            }
        }

        if (App.inlineSearch.collectAsState().value) {
            LibrarySubPage(moreActive = true, content = body)
        } else {
            PlayerTheme {
                Column(modifier = Modifier.fillMaxSize()) { body() }
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
