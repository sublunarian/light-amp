package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.sublunar.amp.App
import com.sublunar.amp.BuildConfig
import com.sublunar.amp.data.LayoutMode
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
import kotlinx.coroutines.launch
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
        // Under Simplified both of these have moved onto the library page: the
        // liked lists are entries there rather than a filter over a tab, and
        // the pages this used to lead to are on it too. What is left here is
        // what this menu is actually for — how the page in front of you looks.
        val simplified = App.layoutMode.collectAsState().value == LayoutMode.SIMPLIFIED
        val likedTab = if (simplified) null else likedFilterTab(page)
        val liked = likedTab != null && likedOnly(likedTab)
        // Shown under both layouts, unlike the liked filter: these narrowings
        // have no other home now that the tag pages are gone.
        val genre = tagSettingOf(page, byComposer = false)
        val composer = tagSettingOf(page, byComposer = true)

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
                // Directly under the source, above the rows that describe how
                // the page is drawn: this one says *what is in it*, which is the
                // first thing to settle and the thing the others then apply to.
                if (likedTab != null) {
                    item {
                        // Named for what it narrows to — "Liked Albums", not
                        // "Liked Only" — through the same helper the page title
                        // uses, so the switch and the header it changes cannot
                        // end up calling the same thing two different things.
                        ToggleRow(tabTitle(likedTab, likedOnly = true), liked) {
                            App.scope.launch { setLikedOnly(likedTab, !liked) }
                            // Straight back to the narrowed list, as choosing in
                            // the picker used to do — seeing the result is the
                            // point of the tap, and the list says which way the
                            // switch went better than the switch does.
                            goBack()
                        }
                    }
                }
                // Only where there is something to choose. A row that states a
                // value and does nothing when tapped reads as a control that is
                // broken rather than as an answer — the fixed orders these
                // pages have (a record plays as it was cut) are better said by
                // the list itself than by a dead row above it.
                if (view.open != null) item { Setting("View", view) }
                if (sort.open != null) item { Setting("Sort by", sort) }
                if (genre != null) item { Setting("Genre", genre) }
                if (composer != null) item { Setting("Composer", composer) }
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
    LibraryPage.LIBRARY -> "Library"
    LibraryPage.DOWNLOADS -> "Downloads"
    LibraryPage.ARTIST_POPULAR -> "Popular Songs"
    // Named after a record, a person, a playlist or a tag, so the page hands
    // its own title over — see MoreScreen.pageTitle.
    LibraryPage.ALBUM -> "Album"
    LibraryPage.ARTIST, LibraryPage.ARTIST_SONGS -> "Artist"
    LibraryPage.PLAYLIST -> "Playlist"
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
 * A page whose order was never yours to choose still answers here, but the
 * caller drops the row rather than drawing it — see Content. The value is kept
 * because working out whether there *is* a choice is the same work as naming it.
 */
@Composable
private fun sortOf(page: LibraryPage): PageSetting = when (page) {
    LibraryPage.ALBUMS -> {
        val sort by App.albumSort.collectAsState()
        val reversed by App.albumSortReversed.collectAsState()
        sortable(albumSortLabel(sort), sort.descendingByNature, reversed) {
            AlbumsSortScreen(it)
        }
    }

    LibraryPage.SONGS, LibraryPage.DOWNLOADS -> {
        val sort by App.songSort.collectAsState()
        val reversed by App.songSortReversed.collectAsState()
        val from = if (page == LibraryPage.DOWNLOADS) "Downloads" else null
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

    // Fixed orders, named as they actually are — see LibraryRepository.
    LibraryPage.ALBUM -> PageSetting("Track order")
    LibraryPage.ARTIST -> PageSetting("Date Released")
    LibraryPage.ARTIST_SONGS -> PageSetting("Album")
    LibraryPage.ARTIST_POPULAR -> PageSetting("Plays")
    LibraryPage.PLAYLIST -> PageSetting("Playlist order")
    LibraryPage.SEARCH -> PageSetting("Library order")
    // A menu of places, in the order the app lists them.
    LibraryPage.LIBRARY -> PageSetting("Fixed")
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
 * The tab this page can be narrowed to liked-only, or null where it can't.
 *
 * A switch rather than a row that opens a picker, which is what this was: liked
 * and all are the only two answers there will ever be, so a page to choose
 * between them was a tap and a screen spent saying what a toggle says in place.
 * Its neighbours keep their pickers because they have more than two answers.
 *
 * Null where the question doesn't arise — including on the pages that used to
 * answer "All" and do nothing when tapped. A switch that cannot be moved is
 * worse than no row at all, where a value that cannot change was merely inert.
 * Plex and the phone's own files have no likes either — see
 * MusicSource.supportsLikes.
 */
@Composable
private fun likedFilterTab(page: LibraryPage): LibraryTab? {
    if (!App.source.collectAsState().value.supportsLikes) return null
    return when (page) {
        LibraryPage.ALBUMS -> LibraryTab.ALBUMS
        LibraryPage.SONGS -> LibraryTab.SONGS
        LibraryPage.ARTISTS -> LibraryTab.ARTISTS
        else -> null
    }
}

/**
 * The genre or composer this page has been narrowed to, where it can be.
 *
 * Only the two lists of things a tag describes, and only where the library
 * actually carries that tag — a server that fills in neither shouldn't offer a
 * row that could only ever say "All". This is what the browsable Genres and
 * Composers pages became: narrowing the list you are on says the same thing as
 * a page per value, without a second way to reach every record.
 */
@Composable
private fun tagSettingOf(page: LibraryPage, byComposer: Boolean): PageSetting? {
    // All read before the branch: a collectAsState behind a condition is a
    // conditional composable call, and the state it subscribes to stops
    // triggering recomposition — the bug AlbumsTab documents.
    val values by (if (byComposer) App.library.composers else App.library.genres)
        .collectAsState()
    val albums = App.albumsTagFilter.collectAsState().value
    val songs = App.songsTagFilter.collectAsState().value
    val tab = when (page) {
        LibraryPage.ALBUMS -> LibraryTab.ALBUMS
        LibraryPage.SONGS -> LibraryTab.SONGS
        else -> return null
    }
    if (values.isEmpty()) return null
    val filter = if (tab == LibraryTab.ALBUMS) albums else songs
    val chosen = if (byComposer) filter.composer else filter.genre
    return PageSetting(
        value = chosen.ifEmpty { "All" },
        open = { TagFilterScreen(it, tab, byComposer) },
    )
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
