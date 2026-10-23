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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.viewModelScope
import com.sublunar.amp.App
import com.sublunar.amp.data.LastSection
import com.sublunar.amp.data.LayoutMode
import com.sublunar.amp.source.Providers
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.PlayerTheme
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How long to wait for the restored queue before giving up on the player. */
private const val RESTORE_PLAYER_MS = 2_000L

sealed interface BootState {
    data object Loading : BootState
    data object NeedsLogin : BootState
    data object Ready : BootState
}

class BootViewModel : LightViewModel<Unit>() {
    // Tab and search state live in LibraryNav, so library sub-pages (which show
    // the bar too) can drive them.
    val currentTab = LibraryNav.currentTab
    val searchActive = LibraryNav.searchActive
    val searchQuery = LibraryNav.searchQuery

    fun setSearchQuery(query: String) = LibraryNav.setQuery(query)
    fun closeSearch() = LibraryNav.closeSearch()

    /**
     * The welcome page only while there is no source at all, never for a missing
     * server: the phone's own music has none.
     */
    val bootState: StateFlow<BootState> = App.settings.activeSource
        .map { if (it == null) BootState.NeedsLogin else BootState.Ready }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BootState.Loading)
}

/**
 * Root screen. Boots the app singletons, then shows the splash, the welcome page
 * (no source yet) or the library shell. Every other screen is pushed on top of
 * this one, so the shell is always the base of the back stack.
 */
@InitialScreen
class BootScreen(private val sealed: SealedLightActivity) : LightScreen<Unit, BootViewModel>(sealed) {

    init {
        root = this
        App.boot(sealed, lightContext)
    }

    private var showing = false

    override fun willShow() {
        showing = true
    }

    override fun willHide() {
        showing = false
    }

    /**
     * Whether this window has reopened where the app was left — see [Content].
     *
     * Held on the screen, not by an effect's key: the root leaves composition
     * whenever a screen is pushed over it, so a `LaunchedEffect(Unit)` there runs
     * again on every return. Once per window rather than per process, so a
     * window Android rebuilds while the app runs on lands too.
     */
    private var landed = false

    override val viewModelClass: Class<BootViewModel> get() = BootViewModel::class.java

    override fun createViewModel(): BootViewModel = BootViewModel()

    // The library is where the rocker is usually pressed, so the root screen
    // forwards it too — see PlaybackController.handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    // Named on the way out: a newer activity may have taken the player over
    // by then, and this back-out must not release it — see App.shutdown.
    override fun onScreenDestroy() {
        if (root === this) root = null
        App.shutdown(sealed)
    }

    companion object {
        /** The newest window's root. An older window closing late can't speak for it. */
        private var root: BootScreen? = null

        /** Whether the library shell is the screen showing: where [backToRoot] stops. */
        val isShowing: Boolean get() = root?.showing == true
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

        // Coming back to the front is a reason to look at the link again: the
        // SDK has no resume hook for a tool, and a process frozen in the
        // background hears about a network change late, if at all. Focus
        // regained asks the system, and the server if it was last seen silent.
        val focused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(focused, state) {
            if (focused && state is BootState.Ready) App.linkMayHaveChanged()
        }

        when (state) {
            BootState.Loading -> Splash()
            // No source yet, so none to leave out of the choices.
            BootState.NeedsLogin -> WelcomeContent(
                entries = Providers.addEntries(emptyList()),
                notes = Providers.addNotes,
                onChoose = { begin(it, thenBack = false) },
            )
            BootState.Ready -> {
                val tab by viewModel.currentTab.collectAsState()
                // Reopen where the app was left: one of the bar's three
                // destinations, never the page beneath it — see LastSection.
                // Once per window — see [landed].
                LaunchedEffect(Unit) {
                    if (landed) return@LaunchedEffect
                    landed = true
                    // Its front page, under the player too: backing out of that lands here.
                    LibraryTab.entries.firstOrNull { it.name == App.settings.lastTab.first() }
                        ?.let { LibraryNav.currentTab.value = it }
                    when (App.settings.lastSection.first()) {
                        LastSection.SEARCH -> LibraryNav.openSearch()
                        LastSection.NOW_PLAYING -> {
                            // The queue is restored asynchronously, so wait a
                            // moment for it rather than opening an empty player.
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
                // The SDK's full-screen editor, Simplified's way of typing a
                // query — see search and editSearch below.
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
                        // Simplified gets the full-screen editor: its bar is the
                        // phone's own three buttons, and the inline keyboard
                        // belongs to the layout with a magnifier in the header.
                        // Standard goes straight to typing on the page itself.
                        search = {
                            if (App.layoutMode.value == LayoutMode.SIMPLIFIED) {
                                LibraryNav.openSearch()
                                openSearchEditor("")
                            } else {
                                LibraryNav.openSearch(withKeyboard = true)
                            }
                        },
                        // The search page's query, tapped under Simplified.
                        editSearch = { current -> openSearchEditor(current) },
                        more = { page -> go { MoreScreen(it, page) } },
                        // Simplified's library button — see LibraryNav.pressLibrary.
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

    /** The library's front page: the index under Simplified, the current tab otherwise. */
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
                AppText("amp", pxSp(LightType.HEADING_PX))
            }
        }
    }
}
