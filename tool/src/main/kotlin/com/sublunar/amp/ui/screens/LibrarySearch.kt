package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.data.Album
import com.sublunar.amp.data.LayoutMode
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.ArtistRow
import com.sublunar.amp.ui.components.CORNER_ICON_PX
import com.sublunar.amp.ui.components.EmptyState
import com.sublunar.amp.ui.components.HEADER_BAR_PX
import com.sublunar.amp.ui.components.INNER_SLOT_PX
import com.sublunar.amp.ui.components.LIST_EDGE_PX
import com.sublunar.amp.ui.components.ListScrollBar
import com.sublunar.amp.ui.components.ROW_ACTION_ICON_PX
import com.sublunar.amp.ui.components.ROW_GAP_PX
import com.sublunar.amp.ui.components.ROW_LEAD_PX
import com.sublunar.amp.ui.components.ROW_TITLE_LINE_PX
import com.sublunar.amp.ui.components.ROW_TITLE_PX
import com.sublunar.amp.ui.components.SCROLLBAR_LANE_PX
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.TrackRow
import com.sublunar.amp.ui.components.appClickable
import com.sublunar.amp.ui.components.listPadding
import com.sublunar.amp.ui.components.rememberListAnchor
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.SimpleLightScreen

/**
 * The bar's search button. Simplified gets the full-screen editor: its bar is the
 * phone's own three buttons, and the inline keyboard belongs to the layout with a
 * magnifier in the header. Standard goes straight to typing on the page itself.
 */
internal fun SimpleLightScreen<*>.startSearch() {
    if (App.settings.layoutMode.value == LayoutMode.SIMPLIFIED) {
        LibraryNav.openSearch()
        editSearch("")
    } else {
        LibraryNav.openSearch(withKeyboard = true)
    }
}

@Composable
internal fun SimpleLightScreen<*>.SearchView(
    /** The list search was started from — what it shows until something is typed. */
    tab: LibraryTab,
) {
    val query by LibraryNav.searchQuery.collectAsState()
    val typing by LibraryNav.typing.collectAsState()
    // While the keyboard is up the field is the query: the keyboard writes into
    // this state and the results follow it keystroke by keystroke.
    val state = rememberTextFieldState(query)
    val live = if (typing) state.text.toString() else query
    LaunchedEffect(live, typing) { if (typing) LibraryNav.setQuery(live) }

    // Keyed on every list search reads, so a re-sync or a switch that keeps the
    // track count still refreshes the results.
    val tracks by App.library.tracks.collectAsState()
    val albums by App.library.albums.collectAsState()
    val artists by App.library.artists.collectAsState()
    val results = remember(live, tracks, albums, artists) { App.library.search(live) }
    val listState = rememberListAnchor("search")

    // The rows take the scrollbar's lane back individually, so the list itself
    // keeps equal margins.
    val lane = px(SCROLLBAR_LANE_PX) - px(LIST_EDGE_PX)
    Column(Modifier.fillMaxSize()) {
        SearchHeader(
            query = live,
            onEdit = {
                // Simplified never raises the inline keyboard: its search is the
                // SDK's own screen.
                if (App.settings.layoutMode.value == LayoutMode.SIMPLIFIED) {
                    editSearch(live)
                } else {
                    LibraryNav.typing.value = true
                }
            },
            onCancel = {
                // The cross abandons the search, query included: back to the
                // list it was started from, as if it never happened.
                state.clearText()
                LibraryNav.setQuery("")
                LibraryNav.typing.value = false
                LibraryNav.closeSearch()
            },
            onMenu = { go { MoreScreen(it, LibraryPage.SEARCH) } },
        )
        Box(modifier = Modifier.weight(1f)) {
            // Nothing typed yet, so the list that was already there: the keyboard
            // opening is not itself a search.
            if (live.isBlank()) {
                TabContent(tab, header = false)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listPadding(end = px(LIST_EDGE_PX)),
                ) {
                    if (results.isEmpty) {
                        item { EmptyState("No results") }
                    }
                    if (results.artists.isNotEmpty()) {
                        item { SectionLabel("Artists") }
                        items(results.artists, key = { "ar-${it.name}" }) { artist ->
                            // A list row like the albums and songs under it: a
                            // result is a thing in the library, not a menu entry.
                            ArtistRow(
                                name = artist.name,
                                imageId = artist.imageId,
                                modifier = Modifier.padding(end = lane),
                                onClick = {
                                    LibraryNav.closeSearch()
                                    openArtist(artist.name, Parent.tab(LibraryTab.ARTISTS))
                                },
                            )
                        }
                    }
                    if (results.albums.isNotEmpty()) {
                        item { SectionLabel("Albums") }
                        items(results.albums, key = { "al-${it.id}" }) { album ->
                            TrackRow(
                                title = album.title,
                                subtitle = album.artist,
                                coverArtId = album.coverArtId,
                                fallback = AppIcons.Album,
                                modifier = Modifier.padding(end = lane),
                                onClick = {
                                    LibraryNav.closeSearch()
                                    openAlbum(album.id, Parent.artist(album.artist))
                                },
                                onLongClick = {
                                    LibraryNav.closeSearch()
                                    go { AlbumActionsScreen(it, album.id) }
                                },
                            )
                        }
                    }
                    if (results.tracks.isNotEmpty()) {
                        item { SectionLabel("Songs") }
                        items(results.tracks, key = { "tr-${it.id}" }) { track ->
                            TrackRow(
                                title = track.title,
                                subtitle = track.artist,
                                coverArtId = track.coverArtId,
                                modifier = Modifier.padding(end = lane),
                                onClick = {
                                    // Search stays up underneath, unlike the two
                                    // branches above, which lay a tab down as the
                                    // parent: back out of the player lands on the
                                    // results, still typed and scrolled.
                                    App.playback.playQueue(listOf(track), 0)
                                    go { NowPlayingScreen(it) }
                                },
                                onLongClick = { openTrackActions(track.id, null) },
                            )
                        }
                    }
                }
                ListScrollBar(listState)
            }
        }
        if (typing) SearchKeyboard(state) { LibraryNav.typing.value = false }
    }
}

/**
 * The search page's header: the query, and a way out.
 *
 * The same header whether or not the keyboard is up: Return puts the keyboard
 * away and the field stays where it was, with the results scrolling under it.
 * The magnifier sits where every cover and glyph below starts, and the page's
 * menu keeps the corner it has everywhere else.
 */
@Composable
private fun SearchHeader(
    query: String,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
    onMenu: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(HEADER_BAR_PX)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .appClickable(onClick = onEdit),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(px(SEARCH_AXIS_PX)))
            AppIcon(AppIcons.Search, size = px(SEARCH_GLYPH_PX))
            Spacer(Modifier.width(px(ROW_GAP_PX)))
            Box(modifier = Modifier.weight(1f)) {
                AppText(
                    text = query.ifEmpty { "Search" },
                    size = pxSp(SEARCH_TEXT_PX),
                    lineHeight = pxSp(ROW_TITLE_LINE_PX),
                    maxLines = 1,
                )
            }
        }
        // The cross takes the slot the magnifier had on the tab this came from,
        // so the button that leaves search is where the one that entered it was.
        // Both slots are AppHeader's: an 80px square hard against the 160px one.
        Box(
            modifier = Modifier
                .width(px(INNER_SLOT_PX))
                .fillMaxHeight()
                .appClickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(AppIcons.Close, size = px(SEARCH_GLYPH_PX))
        }
        Box(
            modifier = Modifier
                .size(px(HEADER_BAR_PX))
                .appClickable(onClick = onMenu),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(AppIcons.Sort, size = px(CORNER_ICON_PX))
        }
    }
}

/** Where the search glyph sits: the list's edge plus a row's lead. */
private const val SEARCH_AXIS_PX = LIST_EDGE_PX + ROW_LEAD_PX

/** The search header's type and glyph, in the rows' sizes so it matches the rows beneath. */
private const val SEARCH_TEXT_PX = ROW_TITLE_PX
private const val SEARCH_GLYPH_PX = ROW_ACTION_ICON_PX
