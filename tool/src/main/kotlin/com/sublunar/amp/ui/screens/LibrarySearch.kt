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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.sublunar.amp.App
import com.sublunar.amp.data.Album
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.ArtistRow
import com.sublunar.amp.ui.components.EmptyState
import com.sublunar.amp.ui.components.HEADER_BAR_PX
import com.sublunar.amp.ui.components.LIST_EDGE_PX
import com.sublunar.amp.ui.components.ListScrollBar
import com.sublunar.amp.ui.components.ROW_TITLE_LINE_PX
import com.sublunar.amp.ui.components.ROW_TITLE_PX
import com.sublunar.amp.ui.components.SCROLLBAR_LANE_PX
import com.sublunar.amp.ui.components.ROW_LEAD_PX
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.TrackRow
import com.sublunar.amp.ui.components.appClickable
import com.sublunar.amp.ui.components.listPadding
import com.sublunar.amp.ui.components.rememberListAnchor
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The bar's search button: straight to the keyboard, in either layout. */
internal fun SimpleLightScreen<*>.startSearch() = askQuery("")

/**
 * The query typed in LightOS's keyboard: submitting shows its results, and
 * backing out leaves the page as it was.
 */
private fun SimpleLightScreen<*>.askQuery(current: String) {
    enterText("Search", current, TextKind.EXACT) { typed ->
        val query = typed.trim()
        if (query.isNotEmpty()) LibraryNav.showResults(query)
    }
}

@Composable
internal fun SimpleLightScreen<*>.SearchView() {
    val query by LibraryNav.searchQuery.collectAsState()

    // A new query searches at once. A change to any list search reads (a re-sync,
    // a switch, a play count) searches again off Main: a new track list means
    // folding every song again, and the results shown stay up meanwhile.
    val tracks by App.library.tracks.collectAsState()
    val albums by App.library.albums.collectAsState()
    val artists by App.library.artists.collectAsState()
    var results by remember(query) { mutableStateOf(App.library.search(query)) }
    LaunchedEffect(query, tracks, albums, artists) {
        results = withContext(Dispatchers.Default) { App.library.search(query) }
    }
    val listState = rememberListAnchor("search")
    // Each new query's results from the top; the same query, come back to from a
    // page it opened, keeps its place.
    LaunchedEffect(query) {
        if (query != LibraryNav.resultsQuery) {
            LibraryNav.resultsQuery = query
            listState.scrollToItem(0)
        }
    }

    // The rows take the scrollbar's lane back individually, so the list itself
    // keeps equal margins.
    val lane = px(SCROLLBAR_LANE_PX) - px(LIST_EDGE_PX)
    Column(Modifier.fillMaxSize()) {
        SearchHeader(
            query = query,
            onEdit = { askQuery(query) },
            onBack = { LibraryNav.closeSearch() },
        )
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = listPadding(end = px(LIST_EDGE_PX)),
            ) {
                if (results.isEmpty) {
                    item { EmptyState("No results") }
                }
                if (results.artists.isNotEmpty()) {
                    item { SectionLabel("Artists", dim = false, startPx = ROW_LEAD_PX) }
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
                    item { SectionLabel("Albums", dim = false, startPx = ROW_LEAD_PX) }
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
                    item { SectionLabel("Songs", dim = false, startPx = ROW_LEAD_PX) }
                    items(results.tracks, key = { "tr-${it.id}" }) { track ->
                        TrackRow(
                            title = track.title,
                            subtitle = track.artist,
                            coverArtId = track.coverArtId,
                            modifier = Modifier.padding(end = lane),
                            onClick = {
                                // A song found is a search finished: back out of
                                // the player lands on the library, not the results.
                                LibraryNav.closeSearch()
                                playFromRow(listOf(track), 0)
                            },
                            onLongClick = { openTrackActions(track.id, null) },
                        )
                    }
                }
            }
            ListScrollBar(listState)
        }
    }
}

/**
 * The search page's header: the back arrow, and the query set as the page's
 * title between the arrow's square and one to match it. Tapping the query
 * opens the keyboard on it again.
 */
@Composable
private fun SearchHeader(query: String, onEdit: () -> Unit, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(HEADER_BAR_PX)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Back where every page has it, AppHeader's 160px corner square and the
        // phone's own chevron.
        Box(
            modifier = Modifier
                .size(px(HEADER_BAR_PX))
                .appClickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            LightIcon(LightIcons.BACK, size = 2f)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .appClickable(onClick = onEdit),
            contentAlignment = Alignment.Center,
        ) {
            AppText(
                text = query,
                size = pxSp(SEARCH_TEXT_PX),
                lineHeight = pxSp(ROW_TITLE_LINE_PX),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(px(HEADER_BAR_PX)))
    }
}

/** The search header's type, in the rows' size so it matches the rows beneath. */
private const val SEARCH_TEXT_PX = ROW_TITLE_PX
