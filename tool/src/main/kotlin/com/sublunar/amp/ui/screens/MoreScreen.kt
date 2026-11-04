package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.sublunar.amp.App
import com.sublunar.amp.BuildConfig
import com.sublunar.amp.data.AlbumSort
import com.sublunar.amp.data.ArtistAlbumOrder
import com.sublunar.amp.data.LayoutMode
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
import com.sublunar.amp.ui.components.LocalMenuHeading
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.components.largeMenuText
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.launch

/**
 * A library page's own menu: where its music comes from, how it is drawn and
 * ordered, and a Filters section (liked, downloaded, genre), each row
 * stating its value. A modifier the page can't change has no row.
 */
class MoreScreen(
    sealed: SealedLightActivity,
    /** The list underneath, whose modifiers this page carries. */
    private val page: LibraryPage,
    /** What that list is called, where its name isn't a constant — an artist's. See [defaultTitle] for the rest. */
    private val pageTitle: String? = null,
) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val active by App.settings.activeSource.state.collectAsState()
        // Read here rather than at the row: a list's items are built outside
        // composition, where a @Composable can't be called.
        val view = viewOf(page)
        val sort = sortOf(page)
        // Under Simplified the liked lists are entries on the library index
        // rather than a filter over a tab — unless Liked as Filter (TRIAL) says
        // otherwise.
        val simplified = App.settings.layoutMode.state.collectAsState().value == LayoutMode.SIMPLIFIED
        val likedAsFilter = App.settings.likedAsFilter.state.collectAsState().value
        val likedTab = likedFilterTab(page).takeUnless { simplified && !likedAsFilter }
        val liked = likedOnly(likedTab)
        // Under both layouts, unlike the liked filter: these narrowings have no
        // other home.
        val genre = genreSettingOf(page)
        // The phone's own music is all on the phone already: nothing to narrow to.
        val downloadedTab = filterTab(page).takeIf { App.source.collectAsState().value.supportsDownloads }
        val downloaded = downloadedOnly(downloadedTab)
        // An artist's songs carry their own, for the one visit — see ArtistSongsVisit.
        val visit = ArtistSongsVisit.view.collectAsState().value.takeIf { page == LibraryPage.ARTIST_SONGS }
        val source = App.source.collectAsState().value

        // Covers the tab bar: a menu that leaves the navigation showing reads as
        // another page rather than something on top of the one you were on.
        PlayerTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                AppHeader(
                    // Named after the page it belongs to, as the sort menus are.
                    title = pageTitle ?: defaultTitle(page),
                    fitTitle = true,
                    onBack = { goBack() },
                    // The three buttons keep Settings on the library page alone.
                    rightAction = if (simplified) null else HeaderAction(AppIcons.Settings) { go { SettingsScreen(it) } },
                )
                CompositionLocalProvider(LocalMenuHeading provides largeMenuText()) {
                    ScrollableList {
                        // Always there with the tabs, even with one source and one
                        // library: the page it opens is also where Sync Now lives.
                        // The three buttons have it at the library page's title.
                        if (!simplified) {
                            item {
                                TextRow(
                                    title = "Source",
                                    value = active?.name,
                                    onClick = { go { SourcesScreen(it) } },
                                )
                            }
                        }
                        // Only where there is something to choose: a fixed order (a
                        // record plays as it was cut) is better said by the list.
                        if (view.open != null) item { Setting("View", view) }
                        if (sort.open != null) item { Setting("Sort by", sort) }
                        // What is in the list, last, and unlabelled: the rows name
                        // themselves. These stay on this page when used, since
                        // several are often set together; back shows the narrowed list.
                        if (likedTab != null) {
                            item {
                                // Plain "Liked", as "Downloaded" is: the page is already
                                // this tab's, and the header says "Liked Albums" once on.
                                ToggleRow("Liked", liked) {
                                    App.scope.launch { setLikedOnly(likedTab, !liked) }
                                }
                            }
                        }
                        if (downloadedTab != null) {
                            item {
                                ToggleRow("Downloaded", downloaded) {
                                    App.scope.launch { setDownloadedOnly(downloadedTab, !downloaded) }
                                }
                            }
                        }
                        if (visit != null && source.supportsLikes) {
                            item {
                                ToggleRow("Liked", visit.liked) {
                                    ArtistSongsVisit.view.value = visit.copy(liked = !visit.liked)
                                }
                            }
                        }
                        if (visit != null && source.supportsDownloads) {
                            item {
                                ToggleRow("Downloaded", visit.downloaded) {
                                    ArtistSongsVisit.view.value = visit.copy(downloaded = !visit.downloaded)
                                }
                            }
                        }
                        if (genre != null) item { Setting("Genre", genre, stay = true) }
                    }
                }
            }
        }
    }

    /** A modifier's row: its name, its value, and a way in where there is one. */
    @Composable
    private fun Setting(
        name: String,
        state: PageSetting,
        /** Come back here after choosing, rather than to the list — for the filters. */
        stay: Boolean = false,
    ) {
        TextRow(
            title = name,
            value = state.value,
            valueTrailing = state.valueTrailing,
            onClick = state.open?.let { factory -> { if (stay) go(factory) else change(factory) } },
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
    // Named after the artist, so the page hands its own title over — see
    // MoreScreen.pageTitle.
    LibraryPage.ARTIST, LibraryPage.ARTIST_SONGS -> "Artist"
}

/**
 * What one modifier is set to on a given page, and where to change it. [open] is
 * null when the page offers no choice, and then [MoreScreen] drops the row.
 */
private class PageSetting(
    val value: String,
    val open: ((SealedLightActivity) -> SimpleLightScreen<Unit>)? = null,
    /** Just after the value, on its line: the sort direction. */
    val valueTrailing: (@Composable () -> Unit)? = null,
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
        val sort by App.settings.albumSort.state.collectAsState()
        val reversed by App.settings.albumSortReversed.state.collectAsState()
        sortable(
            albumSortLabel(sort),
            sort.descendingByNature,
            reversed,
            // A shuffle has no direction for the arrow to claim.
            directionless = sort == AlbumSort.RANDOM,
        ) {
            SortScreen(it, LibraryTab.ALBUMS)
        }
    }

    LibraryPage.SONGS -> {
        val sort by App.settings.songSort.state.collectAsState()
        val reversed by App.settings.songSortReversed.state.collectAsState()
        sortable(songSortLabel(sort), sort.descendingByNature, reversed) {
            SortScreen(it, LibraryTab.SONGS)
        }
    }

    LibraryPage.ARTISTS -> {
        val sort by App.settings.artistSort.state.collectAsState()
        val reversed by App.settings.artistSortReversed.state.collectAsState()
        sortable(artistSortLabel(sort), sort.descendingByNature, reversed) {
            SortScreen(it, LibraryTab.ARTISTS)
        }
    }

    LibraryPage.PLAYLISTS -> {
        val sort by App.settings.playlistSort.state.collectAsState()
        val reversed by App.settings.playlistSortReversed.state.collectAsState()
        sortable(playlistSortLabel(sort), sort.descendingByNature, reversed) {
            SortScreen(it, LibraryTab.PLAYLISTS)
        }
    }

    // Release Type is offered only where the library knows release types; a
    // stored choice of it reads as the dates' order where it doesn't.
    LibraryPage.ARTIST -> {
        val albums by App.library.albums.collectAsState()
        val order by App.settings.artistAlbumOrder.state.collectAsState()
        val shown = if (order == ArtistAlbumOrder.RELEASE_TYPE && !knowsReleaseTypes(albums)) {
            ArtistAlbumOrder.DATE_RELEASED
        } else {
            order
        }
        PageSetting(shown.label, open = { ArtistAlbumOrderScreen(it) })
    }
    // Fixed orders, named as they actually are — see LibraryRepository.
    // Its own order, for the visit — see ArtistSongsVisit.
    LibraryPage.ARTIST_SONGS -> {
        val view by ArtistSongsVisit.view.collectAsState()
        PageSetting(view.sort.label, open = { ArtistSongSortScreen(it) })
    }
    LibraryPage.ARTIST_POPULAR -> PageSetting("Frequently Played")
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
    // `reversed` inverts the option's natural direction, as in SortScreen.
    val descending = naturallyDescending != reversed
    return PageSetting(
        value = label,
        open = open,
        valueTrailing = if (directionless) {
            null
        } else {
            {
                AppIcon(
                    if (descending) AppIcons.ArrowDownward else AppIcons.ArrowUpward,
                    size = px(SORT_ARROW_PX),
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
    return filterTab(page)
}

/** The tab whose lists a page's filters narrow: the albums, songs and artists tabs only. */
private fun filterTab(page: LibraryPage): LibraryTab? = when (page) {
    LibraryPage.ALBUMS -> LibraryTab.ALBUMS
    LibraryPage.SONGS -> LibraryTab.SONGS
    LibraryPage.ARTISTS -> LibraryTab.ARTISTS
    else -> null
}

/**
 * The genre this page has been narrowed to, where it can be: only the albums
 * and songs lists, and only where the library carries genres.
 */
@Composable
private fun genreSettingOf(page: LibraryPage): PageSetting? {
    // All read before the branch — see ARCHITECTURE.md on conditional
    // composable calls.
    val values by App.library.genres.collectAsState()
    val albums = App.albumsTagFilter.collectAsState().value
    val songs = App.songsTagFilter.collectAsState().value
    val tab = when (page) {
        LibraryPage.ALBUMS -> LibraryTab.ALBUMS
        LibraryPage.SONGS -> LibraryTab.SONGS
        else -> return null
    }
    val chosen = (if (tab == LibraryTab.ALBUMS) albums else songs).genre
    // Offered where the library carries genres — or where a filter is set,
    // whatever the library carries: a row that hides while it is narrowing
    // the list leaves no way to un-narrow it.
    if (values.isEmpty() && chosen.isEmpty()) return null
    return PageSetting(
        value = chosen.ifEmpty { "All" },
        open = { TagFilterScreen(it, tab) },
    )
}

class AboutScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        ListScreen(onBack = { goBack() }, title = "About", headingRows = true) {
            Column(
                modifier = Modifier.fillMaxSize().padding(px(40)),
                verticalArrangement = Arrangement.spacedBy(px(26)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppText("amp", pxSp(LightType.HEADING_PX))
                AppText("Version ${BuildConfig.VERSION_NAME}", pxSp(LightType.DETAIL_PX), dim = true)
                // Only when the loopback stream proxy failed, and why: nothing
                // streams without it, and it can't be seen any other way without a cable.
                val proxy by App.streamProxy.state.collectAsState()
                (proxy as? StreamProxy.State.Off)?.let { off ->
                    AppText(
                        "Stream proxy off — ${off.reason}",
                        pxSp(LightType.DETAIL_PX),
                        dim = true,
                        align = TextAlign.Center,
                    )
                }
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

/** The sort direction's arrow: about the picker pages' tick, and a multiple of 3 to stay sharp on the LP3. */
private const val SORT_ARROW_PX = 60
