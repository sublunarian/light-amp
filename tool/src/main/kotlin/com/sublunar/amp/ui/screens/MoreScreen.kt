package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.sublunar.amp.App
import com.sublunar.amp.BuildConfig
import com.sublunar.amp.data.AlbumSort
import com.sublunar.amp.data.LayoutMode
import com.sublunar.amp.data.PlaylistSort
import com.sublunar.amp.data.StreamProxy
import com.sublunar.amp.data.descendingByNature
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.PlayerTheme
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.launch

/**
 * A library page's own menu: where its music comes from, and the ways of looking
 * at it (liked filter, view, sort, genre, composer), each row stating its value.
 * A modifier the page can't change has no row.
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
        // Under Simplified the liked lists are entries on the library index
        // rather than a filter over a tab.
        val simplified = App.layoutMode.collectAsState().value == LayoutMode.SIMPLIFIED
        val likedTab = if (simplified) null else likedFilterTab(page)
        val liked = likedTab != null && likedOnly(likedTab)
        // Under both layouts, unlike the liked filter: these narrowings have no
        // other home.
        val genre = tagSettingOf(page, byComposer = false)
        val composer = tagSettingOf(page, byComposer = true)

        // Covers the tab bar: a menu that leaves the navigation showing reads as
        // another page rather than something on top of the one you were on.
        PlayerTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                AppHeader(
                    // Named after the page it belongs to, as the sort menus are.
                    title = pageTitle ?: defaultTitle(page),
                    fitTitle = true,
                    onBack = { goBack() },
                    rightAction = HeaderAction(AppIcons.Settings) { go { SettingsScreen(it) } },
                )
                ScrollableList(modifier = Modifier.fillMaxSize()) {
                    // Always there, even with one source and one library: the
                    // page it opens is also where Sync Now lives.
                    item {
                        TextRow(
                            title = "Source",
                            value = when {
                                sources.size > 1 -> "${active?.name.orEmpty()} · ${sources.size} sources"
                                else -> active?.name
                            },
                            onClick = { go { SourcesScreen(it) } },
                        )
                    }
                    // Above the rows about how the page is drawn: this one says
                    // what is in it.
                    if (likedTab != null) {
                        item {
                            // Named through the helper the page title uses, so the
                            // switch and the header it changes say the same thing.
                            ToggleRow(tabTitle(likedTab, likedOnly = true), liked) {
                                App.scope.launch { setLikedOnly(likedTab, !liked) }
                                // Straight back to the list: seeing the result is
                                // the point of the tap.
                                goBack()
                            }
                        }
                    }
                    // Only where there is something to choose: a fixed order (a
                    // record plays as it was cut) is better said by the list.
                    if (view.open != null) item { Setting("View", view) }
                    if (sort.open != null) item { Setting("Sort by", sort) }
                    if (genre != null) item { Setting("Genre", genre) }
                    if (composer != null) item { Setting("Composer", composer) }
                }
            }
        }
    }

    /** A modifier's row: its name, its value, and a way in where there is one. */
    @Composable
    private fun Setting(name: String, state: PageSetting) {
        TextRow(
            title = name,
            value = state.value,
            trailing = state.trailing,
            onClick = state.open?.let { factory -> { change(factory) } },
        )
    }

    /**
     * Open a picker, and leave with it once it has been used: choosing a sort is
     * a request to see the reordered list. Backing out of a picker without
     * choosing reports nothing (see LightActivity.deliverResult) and lands here.
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
    LibraryPage.ARTIST_POPULAR -> "Popular Songs"
    // Named after a record, a person, a playlist or a tag, so the page hands
    // its own title over — see MoreScreen.pageTitle.
    LibraryPage.ALBUM -> "Album"
    LibraryPage.ARTIST, LibraryPage.ARTIST_SONGS -> "Artist"
    LibraryPage.PLAYLIST -> "Playlist"
}

/**
 * What one modifier is set to on a given page, and where to change it. [open] is
 * null when the page offers no choice, and then [MoreScreen] drops the row.
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

/** The order the page is in. A fixed order has no [PageSetting.open], so its row is dropped. */
@Composable
private fun sortOf(page: LibraryPage): PageSetting = when (page) {
    LibraryPage.ALBUMS -> {
        val sort by App.albumSort.collectAsState()
        val reversed by App.albumSortReversed.collectAsState()
        sortable(
            albumSortLabel(sort),
            sort.descendingByNature,
            reversed,
            // A shuffle has no direction for the arrow to claim.
            directionless = sort == AlbumSort.RANDOM,
        ) {
            AlbumsSortScreen(it)
        }
    }

    LibraryPage.SONGS -> {
        val sort by App.songSort.collectAsState()
        val reversed by App.songSortReversed.collectAsState()
        sortable(songSortLabel(sort), sort.descendingByNature, reversed) {
            SongsSortScreen(it)
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
    LibraryPage.ARTIST_POPULAR -> PageSetting("Frequently Played")
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
    /** True for an order with no direction — Random — which gets no arrow. */
    directionless: Boolean = false,
    open: (SealedLightActivity) -> SimpleLightScreen<Unit>,
): PageSetting {
    // `reversed` inverts the option's natural direction — as in SortOptions.
    val descending = naturallyDescending != reversed
    return PageSetting(
        value = label,
        open = open,
        trailing = if (directionless) {
            null
        } else {
            {
                AppIcon(
                    if (descending) AppIcons.ArrowDownward else AppIcons.ArrowUpward,
                    size = px(46),
                )
            }
        },
    )
}

/**
 * The tab this page can be narrowed to liked-only, or null where it can't —
 * including a source with no likes (see MusicSource.supportsLikes). A switch
 * rather than a picker: liked and all are the only two answers.
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
 * The genre or composer this page has been narrowed to, where it can be: only
 * the albums and songs lists, and only where the library carries that tag.
 */
@Composable
private fun tagSettingOf(page: LibraryPage, byComposer: Boolean): PageSetting? {
    // All read before the branch — see ARCHITECTURE.md on conditional
    // composable calls.
    val values by (if (byComposer) App.library.composers else App.library.genres)
        .collectAsState()
    val albums = App.albumsTagFilter.collectAsState().value
    val songs = App.songsTagFilter.collectAsState().value
    val tab = when (page) {
        LibraryPage.ALBUMS -> LibraryTab.ALBUMS
        LibraryPage.SONGS -> LibraryTab.SONGS
        else -> return null
    }
    val filter = if (tab == LibraryTab.ALBUMS) albums else songs
    val chosen = if (byComposer) filter.composer else filter.genre
    // Offered where the library carries the tag — or where a filter is set,
    // whatever the library carries: a row that hides while it is narrowing
    // the list leaves no way to un-narrow it.
    if (values.isEmpty() && chosen.isEmpty()) return null
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
                modifier = Modifier.fillMaxSize().padding(px(40)),
                verticalArrangement = Arrangement.spacedBy(px(26)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppText("amp", pxSp(LightType.HEADING_PX))
                AppText("Version ${BuildConfig.VERSION_NAME}", pxSp(LightType.DETAIL_PX), dim = true)
                // Whether the loopback stream proxy came up on this phone, and why
                // not: it can't be seen any other way without a cable.
                val proxy by App.streamProxy.state.collectAsState()
                AppText(
                    when (val state = proxy) {
                        is StreamProxy.State.On -> "Stream proxy on"
                        is StreamProxy.State.Off -> "Stream proxy off — ${state.reason}"
                        StreamProxy.State.Starting -> "Stream proxy starting"
                    },
                    pxSp(LightType.DETAIL_PX),
                    dim = true,
                    align = TextAlign.Center,
                )
                AppText(
                    "Plays music you own: files on this phone, your Bandcamp " +
                        "collection, or your own Plex, Jellyfin, Navidrome or other " +
                        "Subsonic server.",
                    pxSp(LightType.PARAGRAPH_PX),
                    lineHeight = pxSp(LightType.PARAGRAPH_LINE_PX),
                    dim = true,
                    align = TextAlign.Center,
                )
            }
        }
    }
}
