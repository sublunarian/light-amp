package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.sublunar.amp.App
import kotlinx.coroutines.flow.first
import com.sublunar.amp.data.LayoutMode
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.filterNotNull
import com.sublunar.amp.data.LastSection
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.ui.PlayerTheme
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How long to wait for the restored queue before giving up on the player. */
private const val RESTORE_PLAYER_MS = 2_000L

sealed interface BootState {
    data object Loading : BootState
    data object NeedsLogin : BootState
    data object Ready : BootState
}

class BootViewModel : LightViewModel<Unit>() {
    // Tab + inline-search state lives in LibraryNav so library sub-pages (which
    // also show the tab bar and the search button) can drive it.
    val currentTab = LibraryNav.currentTab
    val searchActive = LibraryNav.searchActive
    val searchQuery = LibraryNav.searchQuery

    fun openSearch() = LibraryNav.openSearch()
    fun setSearchQuery(query: String) = LibraryNav.setQuery(query)
    fun closeSearch() = LibraryNav.closeSearch()

    /**
     * Login is only for a first source, not for a missing *server*.
     *
     * Keyed on the source rather than on the connection: the phone's own music
     * has no server behind it, so a config-shaped test sent anyone who selected
     * Local Music straight back to the login form.
     */
    val bootState: StateFlow<BootState> = App.settings.activeSource
        .map { if (it == null) BootState.NeedsLogin else BootState.Ready }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BootState.Loading)
}

/**
 * Root screen. Boots the app singletons, then renders splash / login / home
 * inline based on whether a server is configured. Every other screen is pushed
 * on top of this one, so the home menu is always the base of the back stack.
 */
@InitialScreen
class BootScreen(sealed: SealedLightActivity) : LightScreen<Unit, BootViewModel>(sealed) {

    init {
        App.boot(sealed, lightContext)
    }

    override val viewModelClass: Class<BootViewModel> get() = BootViewModel::class.java

    override fun createViewModel(): BootViewModel = BootViewModel()

    // The library is where the rocker is usually pressed, so the root screen
    // forwards it too — see PlaybackController.handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    override fun onScreenDestroy() {
        App.shutdown()
    }

    private fun createPlaylistFlow() {
        navigateTo<String?>(
            { TextEntryScreen(it, title = "New Playlist") },
            resultCallback = { name ->
                if (!name.isNullOrBlank()) App.scope.launch { App.library.createPlaylist(name) }
            },
        )
    }

    @Composable
    override fun Content() {
        val state by viewModel.bootState.collectAsState()

        LaunchedEffect(state) {
            // Runs in the app scope so it survives navigation into a list.
            if (state is BootState.Ready) App.library.syncInBackground()
        }

        when (state) {
            BootState.Loading -> Splash()
            BootState.NeedsLogin -> WelcomeContent(
                onSubsonic = { go { ServerScreen(it, sourceId = null, adding = true) } },
                onPlex = { go { PlexLinkScreen(it) } },
                onJellyfin = { go { JellyfinLinkScreen(it) } },
                onLocal = {
                    App.scope.launch {
                        val local = MusicSource.local()
                        App.settings.saveSource(local)
                        App.settings.setActiveSource(local.id)
                    }
                },
            )
            BootState.Ready -> {
                val tab by viewModel.currentTab.collectAsState()
                // Reopen where the app was left: one of the bar's three
                // destinations, never the page beneath it — see LastSection.
                // Once per run, guarded by LibraryNav, because this composable
                // re-enters every time a pushed screen pops off it.
                LaunchedEffect(Unit) {
                    if (!LibraryNav.claimFirstLanding()) return@LaunchedEffect
                    when (App.settings.lastSection.first()) {
                        LastSection.SEARCH -> LibraryNav.openSearch()
                        LastSection.NOW_PLAYING -> {
                            // The queue is restored asynchronously, so wait a
                            // moment for it rather than opening a player with
                            // nothing in it. If nothing arrives, the library is
                            // the honest place to land.
                            val track = withTimeoutOrNull(RESTORE_PLAYER_MS) {
                                App.playback.currentTrack.filterNotNull().first()
                            }
                            if (track != null) go { NowPlayingScreen(it) } else openLibrary()
                        }
                        LastSection.LIBRARY -> openLibrary()
                    }
                }
                val libraryIndex by LibraryNav.libraryIndex.collectAsState()
                val searchActive by viewModel.searchActive.collectAsState()
                val searchQuery by viewModel.searchQuery.collectAsState()
                // The SDK's full-screen editor, which is what Simplified uses
                // for search — see the actions below. A local rather than a
                // ShellActions member because two of them open the same thing.
                val openSearchEditor: (String) -> Unit = { current ->
                    navigateTo<String?>(
                        {
                            TextEntryScreen(
                                it,
                                title = "Search",
                                initial = current,
                                submitLabel = "SEARCH",
                            )
                        },
                        resultCallback = { text ->
                            if (text != null) viewModel.setSearchQuery(text)
                        },
                    )
                }
                LibraryShell(
                    currentTab = tab,
                    onSelectTab = { LibraryNav.tapTab(it) },
                    libraryIndex = libraryIndex,
                    searchActive = searchActive,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { viewModel.setSearchQuery(it) },
                    onSearchClose = { viewModel.closeSearch() },
                    actions = ShellActions(
                        nowPlaying = { go { NowPlayingScreen(it) } },
                        settings = { go { SettingsScreen(it) } },
                        // Simplified gets the SDK's own full-screen editor, the
                        // way it always did: its bar is the phone's own three
                        // buttons, and the inline keyboard belongs to the layout
                        // that puts a magnifier in the header. Expanded goes
                        // straight to typing on the page itself — the list stays
                        // underneath until something is actually typed.
                        search = {
                            if (App.layoutMode.value == LayoutMode.SIMPLIFIED) {
                                LibraryNav.openSearch()
                                openSearchEditor("")
                            } else {
                                LibraryNav.openSearch(withKeyboard = true)
                            }
                        },
                        // The header field on the search page, which under
                        // Simplified reopens that same editor rather than
                        // raising the inline keyboard.
                        editSearch = { current -> openSearchEditor(current) },
                        more = { page -> go { MoreScreen(it, page) } },
                        // Simplified's centre button: the library index, which
                        // also drops search if that is what is showing.
                        browse = { LibraryNav.pressLibrary() },
                        openAlbum = { id, parent -> openAlbum(id, parent) },
                        openArtist = { name, parent -> openArtist(name, parent) },
                        openPlaylist = { id, name -> go { PlaylistDetailScreen(it, id, name) } },
                        trackOptions = { id, selection -> openTrackActions(id, selection) },
                        selectionActions = { tracks, selection ->
                            openSelectionActions(tracks, selection)
                        },
                        albumOptions = { id -> go { AlbumActionsScreen(it, id) } },
                        artistOptions = { name -> go { ArtistActionsScreen(it, name) } },
                        playlistOptions = { id, name -> go { PlaylistActionsScreen(it, id, name) } },
                        newPlaylist = { createPlaylistFlow() },
                    ),
                )
            }
        }
    }

    /**
     * The library's own front page: the index under Simplified, and under the
     * standard layout the tab that was current, which is all it has.
     */
    private suspend fun openLibrary() {
        if (App.settings.layoutMode.first() == LayoutMode.SIMPLIFIED) {
            LibraryNav.libraryIndex.value = true
        }
    }

    @Composable
    private fun Splash() {
        PlayerTheme {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                LightText(text = "amp", variant = LightTextVariant.Heading)
            }
        }
    }
}
