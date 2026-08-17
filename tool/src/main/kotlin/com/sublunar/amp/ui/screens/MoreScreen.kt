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
import com.sublunar.amp.data.PlaylistSort
import com.sublunar.amp.data.descendingByNature
import com.sublunar.amp.ui.PlayerTheme
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.n
import com.sublunar.amp.ui.nSp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen

/**
 * The page's own menu: where its music comes from, and the three ways of
 * looking at it.
 *
 * One place for all of them. View, sort and filter used to be spread across a
 * title tap, a heart in a menu's corner and a section folded into the sort
 * list — three different gestures, none of which said what it was currently
 * set to without being opened. Here each states its value on its own line.
 *
 * Every row shows whether or not the page can change it. A song list has one
 * layout and a record has one order, and saying so is more use than a row that
 * silently isn't there: an absent row reads as a missing feature, where "View:
 * List" reads as the answer. Those rows don't take a press — see [TextRow].
 */
class MoreScreen(
    sealed: SealedLightActivity,
    /** The list underneath, whose modifiers this page carries. */
    private val page: LibraryPage,
    /**
     * What that list is called, where its name isn't a constant — a record, an
     * artist, a playlist, a genre. See [defaultTitle] for the rest.
     */
    private val pageTitle: String? = null,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val active by App.settings.activeSource.collectAsState(initial = null)
        // Read here rather than at the row: a list's items are built outside
        // composition, where a @Composable can't be called.
        val view = viewOf(page)
        val sort = sortOf(page)
        val filter = filterOf(page)

        // Covers the tab bar rather than sitting above it: this is the library
        // page's own menu, opened from its header, and a menu that leaves the
        // navigation showing underneath reads as another page rather than as
        // something on top of the one you were on.
        val body: @Composable ColumnScope.() -> Unit = {
            AppHeader(
                // Named after the page it belongs to, the way the sort menus
                // are: this is that page's menu, and a header reading "More"
                // over a row reading "More" says nothing about where you are.
                title = pageTitle ?: defaultTitle(page),
                fitTitle = true,
                onBack = { goBack() },
                // No search here: this page is a short menu you read rather than
                // a list you look through, and search belongs to the library
                // pages it would send you back to anyway.
                rightAction = HeaderAction(AppIcons.Settings) { go { SettingsScreen(it) } },
            )
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                // Where the music comes from, and the only row here that isn't
                // the page's own — every library page is a view of one source,
                // so it heads the list the rest of it describes.
                item {
                    TextRow(
                        title = "Source",
                        subtitle = when {
                            sources.size > 1 -> "${active?.name.orEmpty()} · ${sources.size} sources"
                            else -> active?.name
                        },
                        onClick = { go { SourcesScreen(it) } },
                    )
                }
                item { Setting("View", view) }
                item { Setting("Sort by", sort) }
                // Absent, not inert, where the source has no likes: the other
                // two rows state a value because the page has one either way,
                // but a Plex library has nothing this could ever be set to.
                if (filter != null) item { Setting("Filter", filter) }
                // The pages that aren't views of the one you are on: a genre
                // list, the compilations, what's on the phone. They were the
                // whole of this page before the modifiers arrived, and they are
                // still what "more" means once the modifiers have their say.
                item { TextRow(title = "More") { go { MorePagesScreen(it) } } }
            }
        }

        PlayerTheme {
            Column(modifier = Modifier.fillMaxSize()) { body() }
        }
    }

    /** A modifier's row: its name, its value, and a way in where there is one. */
    @Composable
    private fun Setting(name: String, state: PageSetting) {
        TextRow(
            title = name,
            subtitle = state.value,
            trailing = state.trailing,
            onClick = state.open?.let { factory -> { change(factory) } },
        )
    }

    /**
     * Open a picker, and stand aside once it has been used.
     *
     * Choosing a sort is a request to see the reordered list, not to come back
     * here — so the picker reports its pick and this page leaves with it,
     * putting the list one tap below the choice. Backing out of a picker
     * without choosing reports nothing (see LightActivity.deliverResult) and
     * lands here, which is where you were.
     */
    private fun change(factory: (SealedLightActivity) -> SimpleLightScreen<Unit>) {
        navigateTo(factory, resultCallback = { goBack() })
    }
}

/** What the page is called, for the pages whose name never changes. */
@Composable
private fun defaultTitle(page: LibraryPage): String = when (page) {
    LibraryPage.ALBUMS -> tabTitle(LibraryTab.ALBUMS, likedOnly(LibraryTab.ALBUMS))
    LibraryPage.SONGS -> tabTitle(LibraryTab.SONGS, likedOnly(LibraryTab.SONGS))
    LibraryPage.ARTISTS -> tabTitle(LibraryTab.ARTISTS, likedOnly(LibraryTab.ARTISTS))
    LibraryPage.PLAYLISTS -> LibraryTab.PLAYLISTS.title
    LibraryPage.SEARCH -> "Search"
    LibraryPage.GENRES -> "Genres"
    LibraryPage.COMPOSERS -> "Composers"
    LibraryPage.COMPILATIONS -> "Compilations"
    LibraryPage.DOWNLOADS -> "Downloaded Songs"
    LibraryPage.ARTIST_POPULAR -> "Popular Songs"
    // Named after a record, a person, a playlist or a tag, so the page hands
    // its own title over — see MoreScreen.pageTitle.
    LibraryPage.ALBUM -> "Album"
    LibraryPage.ARTIST, LibraryPage.ARTIST_SONGS -> "Artist"
    LibraryPage.PLAYLIST -> "Playlist"
    LibraryPage.TAG_SONGS -> "Songs"
}

/**
 * What one modifier is set to on a given page, and where to change it.
 *
 * [open] is null when the page offers no choice, which is the common case: a
 * song list is a list, a record plays in its own order. The row still appears —
 * see [MoreScreen].
 */
private class PageSetting(
    val value: String,
    val open: ((SealedLightActivity) -> SimpleLightScreen<Unit>)? = null,
    val trailing: (@Composable () -> Unit)? = null,
)

/**
 * List or grid.
 *
 * Only album lists have the choice, and only while artwork is on — a grid of
 * blank squares is not a way of looking at anything. The albums tab and an
 * artist's discography keep separate answers, so each names its own screen.
 */
@Composable
private fun viewOf(page: LibraryPage): PageSetting {
    val hidden = App.hideArtwork.collectAsState().value
    val grid = when (page) {
        LibraryPage.ALBUMS -> App.albumGrid.collectAsState().value
        LibraryPage.ARTIST -> App.artistAlbumGrid.collectAsState().value
        else -> false
    }
    val choosable = !hidden && (page == LibraryPage.ALBUMS || page == LibraryPage.ARTIST)
    return PageSetting(
        value = if (grid) "Grid" else "List",
        open = if (!choosable) null else {
            { AlbumViewScreen(it, forArtist = page == LibraryPage.ARTIST) }
        },
    )
}

/**
 * The order the page is in.
 *
 * The pages with no menu still answer: they are in an order, it just isn't one
 * you chose. Naming it is the point of the row — "Track order" says a record
 * plays as it was cut, where a blank would only say the app forgot to ask.
 */
@Composable
private fun sortOf(page: LibraryPage): PageSetting = when (page) {
    LibraryPage.ALBUMS, LibraryPage.COMPILATIONS -> {
        val sort by App.albumSort.collectAsState()
        val reversed by App.albumSortReversed.collectAsState()
        val from = if (page == LibraryPage.COMPILATIONS) "Compilations" else null
        sortable(albumSortLabel(sort), sort.descendingByNature, reversed) {
            AlbumsSortScreen(it, from)
        }
    }

    LibraryPage.SONGS, LibraryPage.DOWNLOADS -> {
        val sort by App.songSort.collectAsState()
        val reversed by App.songSortReversed.collectAsState()
        val from = if (page == LibraryPage.DOWNLOADS) "Downloaded Songs" else null
        sortable(songSortLabel(sort), sort.descendingByNature, reversed) {
            SongsSortScreen(it, from)
        }
    }

    LibraryPage.ARTISTS -> {
        val sort by App.artistSort.collectAsState()
        val reversed by App.artistSortReversed.collectAsState()
        sortable(artistSortLabel(sort), sort.descendingByNature, reversed) {
            ArtistsSortScreen(it)
        }
    }

    LibraryPage.PLAYLISTS -> {
        val sort by App.settings.playlistSort.collectAsState(initial = PlaylistSort.RECENTLY_UPDATED)
        val reversed by App.settings.playlistSortReversed.collectAsState(initial = false)
        sortable(playlistSortLabel(sort), sort.descendingByNature, reversed) {
            PlaylistsSortScreen(it)
        }
    }

    LibraryPage.GENRES, LibraryPage.COMPOSERS -> {
        val sort by App.tagSort.collectAsState()
        val reversed by App.tagSortReversed.collectAsState()
        val from = if (page == LibraryPage.COMPOSERS) "Composers" else "Genres"
        sortable(tagSortLabel(sort), sort.descendingByNature, reversed) {
            TagsSortScreen(it, from)
        }
    }

    // Fixed orders, named as they actually are — see LibraryRepository.
    LibraryPage.ALBUM -> PageSetting("Track order")
    LibraryPage.ARTIST -> PageSetting("Date Released")
    LibraryPage.ARTIST_SONGS -> PageSetting("Album")
    LibraryPage.ARTIST_POPULAR -> PageSetting("Plays")
    LibraryPage.PLAYLIST -> PageSetting("Playlist order")
    LibraryPage.TAG_SONGS, LibraryPage.SEARCH -> PageSetting("Library order")
}

/** A changeable order, with the arrow the sort menus use for its direction. */
private fun sortable(
    label: String,
    naturallyDescending: Boolean,
    reversed: Boolean,
    open: (SealedLightActivity) -> SimpleLightScreen<Unit>,
): PageSetting {
    // `reversed` inverts the option's natural direction — as in SortOptions.
    val descending = naturallyDescending != reversed
    return PageSetting(
        value = label,
        open = open,
        trailing = {
            AppIcon(
                if (descending) AppIcons.ArrowDownward else AppIcons.ArrowUpward,
                size = n(18),
            )
        },
    )
}

/**
 * What the page has been narrowed to, or null where nothing can narrow it.
 *
 * Liked is the only narrowing the app has, and only the three tabs that own a
 * kind of thing can be narrowed by it.
 */
@Composable
private fun filterOf(page: LibraryPage): PageSetting? {
    // Null means the question doesn't arise. Liked is the only narrowing the
    // app has, and Plex and the phone's own files have no likes to narrow by —
    // see MusicSource.supportsLikes — so there is no filter to report, rather
    // than one that happens to be off.
    if (!App.source.collectAsState().value.supportsLikes) return null
    val tab = when (page) {
        LibraryPage.ALBUMS -> LibraryTab.ALBUMS
        LibraryPage.SONGS -> LibraryTab.SONGS
        LibraryPage.ARTISTS -> LibraryTab.ARTISTS
        else -> null
    }
    // Everywhere else the row reads "All", which is true and is the answer to
    // the question the row asks — a record's tracks are all of them.
    if (tab == null) return PageSetting("All")
    return PageSetting(
        value = if (likedOnly(tab)) "Liked" else "All",
        open = { FilterScreen(it, tab) },
    )
}

/**
 * The library pages that aren't a view of the one you are on.
 *
 * Each opens as a peer of the tabs rather than a page of this one — More is a
 * way in, like the tab bar, not somewhere above them to go back up to. See
 * openLibraryPage.
 */
class MorePagesScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val source by App.source.collectAsState()
        // Each of these appears only when the library has something to put in it:
        // a server that doesn't tag composers shouldn't offer a Composers page.
        val genres by App.library.genres.collectAsState()
        val composers by App.library.composers.collectAsState()
        val compilations by App.library.compilations.collectAsState()

        ListScreen(onBack = { goBack() }, title = "More") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
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
