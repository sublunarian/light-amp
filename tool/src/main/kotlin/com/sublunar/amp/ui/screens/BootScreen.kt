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

    /**
     * This screen *is* the tabs, so while it shows, no page that isn't a tab
     * does — see [LibraryNav.offTab].
     *
     * The SDK's own hook rather than a composition effect: it fires on the way
     * back down as well (goBack and popToRoot both announce the screen they
     * reveal), which is every path that can leave a standalone page.
     */
    override fun willShow() {
        LibraryNav.offTab.value = false
    }

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
                // A header's search button goes straight to typing, wherever it
                // was pressed — the sub-page that requested it has usually been
                // unwound by the time this runs, so the root does the pushing.
                val wantsKeyboard by LibraryNav.pendingKeyboard.collectAsState()
                LaunchedEffect(wantsKeyboard) {
                    if (!wantsKeyboard) return@LaunchedEffect
                    LibraryNav.pendingKeyboard.value = false
                    navigateTo<String?>(
                        { TextEntryScreen(it, title = "Search", submitLabel = "SEARCH") },
                        resultCallback = { text ->
                            if (text != null) viewModel.setSearchQuery(text)
                        },
                    )
                }
                val searchActive by viewModel.searchActive.collectAsState()
                val searchQuery by viewModel.searchQuery.collectAsState()
                LibraryShell(
                    currentTab = tab,
                    onSelectTab = { LibraryNav.selectTab(it) },
                    searchActive = searchActive,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { viewModel.setSearchQuery(it) },
                    onSearchClose = { viewModel.closeSearch() },
                    onSearchClear = { LibraryNav.clearSearch() },
                    actions = ShellActions(
                        nowPlaying = { go { NowPlayingScreen(it) } },
                        settings = { go { SettingsScreen(it) } },
                        // Straight to the keyboard, so searching is still one tap
                        // even though the editor is a screen of its own now.
                        // Straight to the keyboard: reaching for search from a
                        // list means you have something to type. The field starts
                        // empty — the previous results are still underneath, and
                        // backing out returns to them.
                        search = { LibraryNav.openSearch(withKeyboard = true) },
                        editSearch = { current ->
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
                        },
                        more = { go { MoreScreen(it) } },
                        openAlbum = { id, parent -> openAlbum(id, parent) },
                        openArtist = { name, parent -> openArtist(name, parent) },
                        openPlaylist = { id, name -> go { PlaylistDetailScreen(it, id, name) } },
                        albumsSort = { go { AlbumsSortScreen(it) } },
                        albumView = { go { AlbumViewScreen(it) } },
                        songsSort = { go { SongsSortScreen(it) } },
                        artistsSort = { go { ArtistsSortScreen(it) } },
                        playlistsSort = { go { PlaylistsSortScreen(it) } },
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
