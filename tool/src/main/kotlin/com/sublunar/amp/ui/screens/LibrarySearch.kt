package com.sublunar.amp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import com.sublunar.amp.App
import com.sublunar.amp.data.Album
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.ArtistRow
import com.sublunar.amp.ui.components.CORNER_ICON_PX
import com.sublunar.amp.ui.components.EmptyState
import com.sublunar.amp.ui.components.HEADER_BAR_PX
import com.sublunar.amp.ui.components.LIST_EDGE_PX
import com.sublunar.amp.ui.components.ListScrollBar
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
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The bar's search button: straight to typing on the page itself, in either layout. */
internal fun SimpleLightScreen<*>.startSearch() {
    LibraryNav.openSearch(withKeyboard = true)
}

@Composable
internal fun SimpleLightScreen<*>.SearchView() {
    val query by LibraryNav.searchQuery.collectAsState()
    val typing by LibraryNav.typing.collectAsState()
    // While the keyboard is up the field is the query: the keyboard writes into
    // this state and the results follow it keystroke by keystroke.
    val state = rememberTextFieldState(query)
    val live = if (typing) state.text.toString() else query
    LaunchedEffect(live, typing) { if (typing) LibraryNav.setQuery(live) }

    // A keystroke searches at once. A change to any list search reads (a re-sync,
    // a switch, a play count) searches again off Main: a new track list means
    // folding every song again, and the results shown stay up meanwhile.
    val tracks by App.library.tracks.collectAsState()
    val albums by App.library.albums.collectAsState()
    val artists by App.library.artists.collectAsState()
    var results by remember(live) { mutableStateOf(App.library.search(live)) }
    LaunchedEffect(live, tracks, albums, artists) {
        results = withContext(Dispatchers.Default) { App.library.search(live) }
    }
    val listState = rememberListAnchor("search")
    // Each new query's results from the top; the same query, come back to from a
    // page it opened, keeps its place.
    LaunchedEffect(live) {
        if (live != LibraryNav.resultsQuery) {
            LibraryNav.resultsQuery = live
            listState.scrollToItem(0)
        }
    }
    // Scrolling the results puts the keyboard away, so the whole list shows.
    LaunchedEffect(listState, typing) {
        if (!typing) return@LaunchedEffect
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) LibraryNav.typing.value = false
        }
    }

    // The rows take the scrollbar's lane back individually, so the list itself
    // keeps equal margins.
    val lane = px(SCROLLBAR_LANE_PX) - px(LIST_EDGE_PX)
    // Back abandons the search, query included: back to the list it was
    // started from, as if it never happened.
    val cancel = {
        state.clearText()
        LibraryNav.setQuery("")
        LibraryNav.typing.value = false
        LibraryNav.closeSearch()
    }
    // The cross empties the field and brings the keyboard back for another try.
    val clear = {
        state.clearText()
        LibraryNav.setQuery("")
        LibraryNav.typing.value = true
    }
    Column(Modifier.fillMaxSize()) {
        SearchHeader(
            query = live,
            // While typing, where the next key lands; a tap or drag in the
            // field moves it, raising the keyboard if it was down.
            cursor = if (typing) state.selection.min else null,
            onPlaceCursor = { offset ->
                state.edit { selection = TextRange(offset) }
                LibraryNav.typing.value = true
            },
            onEdit = { LibraryNav.typing.value = true },
            onBack = cancel,
            onClear = clear,
        )
        Box(modifier = Modifier.weight(1f)) {
            // Nothing typed yet, so nothing listed: results come with the first
            // keystroke.
            if (live.isNotBlank()) {
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
                                // Over the results, so back returns to them.
                                onClick = { openArtist(artist.name, Parent.Here) },
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
                                onClick = { openAlbum(album.id, Parent.Here) },
                                onLongClick = { go { AlbumActionsScreen(it, album.id) } },
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
        if (typing) {
            // Return puts the keyboard away to show the results, "No results"
            // included, so the bar is back to choose from. With nothing typed
            // there is nothing to show, so it goes back to the library.
            SearchKeyboard(state) {
                if (state.text.isBlank()) {
                    LibraryNav.closeSearch()
                } else {
                    LibraryNav.typing.value = false
                }
            }
        }
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
    /** Where the cursor is drawn in [query]; null draws none. */
    cursor: Int?,
    /** Moves the cursor to a tapped spot in the query; null leaves taps to [onEdit]. */
    onPlaceCursor: ((Int) -> Unit)?,
    onEdit: () -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
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
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .appClickable(onClick = onEdit),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QueryField(query, cursor, onPlaceCursor, Modifier.weight(1f))
        }
        // The cross where More is elsewhere: it clears the field, so it shows
        // only while there is something in it. Its square stays, so the query
        // doesn't move when it comes and goes.
        Box(
            modifier = Modifier
                .size(px(HEADER_BAR_PX))
                .then(if (query.isEmpty()) Modifier else Modifier.appClickable(onClick = onClear)),
            contentAlignment = Alignment.Center,
        ) {
            if (query.isNotEmpty()) AppIcon(AppIcons.Close, size = px(CORNER_ICON_PX))
        }
    }
}

/**
 * The query, with the cursor drawn as the SDK's own editor draws it: a steady
 * line at the position, not a blinking one. A tap or a drag anywhere in the
 * field's height puts the cursor under the finger.
 */
@Composable
private fun QueryField(
    query: String,
    cursor: Int?,
    onPlaceCursor: ((Int) -> Unit)?,
    modifier: Modifier,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Only the finger's x matters on one line; the y is the line's middle, so
    // the whole header's height is the field.
    fun offsetAt(x: Float): Int {
        if (query.isEmpty()) return 0
        val line = layout ?: return query.length
        return line.getOffsetForPosition(Offset(x, line.size.height / 2f)).coerceIn(0, query.length)
    }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .then(
                if (onPlaceCursor == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(query) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            onPlaceCursor(offsetAt(down.position.x))
                            drag(down.id) { change ->
                                onPlaceCursor(offsetAt(change.position.x))
                                change.consume()
                            }
                        }
                    }
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box {
            AppText(
                text = query.ifEmpty { "Search" },
                size = pxSp(SEARCH_TEXT_PX),
                lineHeight = pxSp(ROW_TITLE_LINE_PX),
                maxLines = 1,
                // Cut from the front while typing, so the end you type at stays in view.
                overflow = if (cursor != null) TextOverflow.StartEllipsis else TextOverflow.Ellipsis,
                onTextLayout = { layout = it },
            )
            val line = layout
            if (cursor != null && line != null) {
                // Held to the text this layout measured, which trails a keystroke
                // by a frame: the query can already be a letter longer. An empty
                // query draws the placeholder, and the cursor sits before it.
                val measured = if (query.isEmpty()) 0 else line.layoutInput.text.length
                val rect = line.getCursorRect(cursor.coerceIn(0, measured))
                Box(
                    modifier = Modifier
                        .offset { IntOffset(rect.left.toInt(), rect.top.toInt()) }
                        .width(px(CURSOR_WIDTH_PX))
                        .height(with(LocalDensity.current) { rect.height.toDp() })
                        .background(LightThemeTokens.colors.content),
                )
            }
        }
    }
}

/** The SDK editor's cursor: 2dp, 6px on the LP3. */
private const val CURSOR_WIDTH_PX = 6

/** The search header's type, in the rows' size so it matches the rows beneath. */
private const val SEARCH_TEXT_PX = ROW_TITLE_PX
