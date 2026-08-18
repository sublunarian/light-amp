package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sublunar.amp.App
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sublunar.amp.data.shuffled
import com.sublunar.amp.ui.PlayerTheme
import com.sublunar.amp.ui.components.AlphabetIndex
import com.sublunar.amp.ui.components.HEADER_BAR_PX
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.ArtistRow
import com.sublunar.amp.ui.components.INDEX_STRIP_PX
import com.sublunar.amp.ui.components.LIST_EDGE_PX
import com.sublunar.amp.ui.components.ListScrollBar
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.listPadding
import com.sublunar.amp.ui.components.SCROLLBAR_LANE_PX
import com.sublunar.amp.ui.components.rememberScrollTarget
import androidx.compose.foundation.lazy.grid.GridItemSpan
import com.sublunar.amp.ui.components.AlbumGrid
import com.sublunar.amp.data.Album
import com.sublunar.amp.ui.components.PlayAllRow
import com.sublunar.amp.ui.components.SplitActionRow
import com.sublunar.amp.data.LocalLibrary
import com.sublunar.amp.data.LayoutMode
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.Track
import com.sublunar.amp.ui.components.SelectionState
import com.sublunar.amp.ui.components.rememberGridAnchor
import com.sublunar.amp.ui.components.rememberListAnchor
import com.sublunar.amp.ui.components.rememberSelection
import com.sublunar.amp.ui.components.SelectionHeader
import com.sublunar.amp.ui.components.TrackRow
import com.sublunar.amp.ui.n

import com.sublunar.amp.ui.nSp
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.sublunar.amp.ui.components.appClickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import com.sublunar.amp.ui.components.EmptyState
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.TextRow

/**
 * Height of the app's bottom bars in LP3 physical pixels — the same 4 grid units
 * (160px) the stock LightOS bottom bar uses. Shared by the library tab bar and
 * the Now Playing transport so both sit on the same baseline.
 */
const val BOTTOM_BAR_PX = 160

/** Reading the phone's own music; see LocalLibrary and lighttool.toml. */
private val READ_MEDIA_AUDIO = LocalLibrary.PERMISSION

enum class LibraryTab(val title: String) {
    PLAYLISTS("Playlists"),
    ARTISTS("Artists"),
    SONGS("Songs"),
    ALBUMS("Albums"),
}

internal fun iconFor(tab: LibraryTab): ImageVector = when (tab) {
    LibraryTab.PLAYLISTS -> AppIcons.QueueMusic
    LibraryTab.ARTISTS -> AppIcons.RecordVoiceOver
    LibraryTab.SONGS -> AppIcons.MusicNote
    LibraryTab.ALBUMS -> AppIcons.AlbumStack
}

class ShellActions(
    val nowPlaying: () -> Unit,
    val settings: () -> Unit,
    val search: () -> Unit,
    /** Opens the full-screen LP3 keyboard to edit the search query. */
    val editSearch: (String) -> Unit,
    /** More carries the showing page's modifiers up with it — see [LibraryPage]. */
    val more: (LibraryPage) -> Unit,
    /** Simplified's centre button: the library index — see [LibraryNav]. */
    val browse: () -> Unit,
    /** The two tag lists, opened from that index as peers of the tabs. */
    val genres: () -> Unit,
    val composers: () -> Unit,
    /**
     * Each takes the page back should land on — a tab list is the parent of what
     * it opens, while a search result is a jump and names the hierarchy it
     * belongs to instead of stacking on top of the results. See [Parent].
     */
    val openAlbum: (String, Parent) -> Unit,
    val openArtist: (String, Parent) -> Unit,
    val openPlaylist: (String, String) -> Unit,
    val trackOptions: (String, SelectionState?) -> Unit,
    /** Opens the bulk-action sheet for a multi-selection. */
    val selectionActions: (List<Track>, SelectionState) -> Unit,
    val albumOptions: (String) -> Unit,
    /** Long-press on an artist: the only way to like one. */
    val artistOptions: (String) -> Unit,
    val playlistOptions: (String, String) -> Unit,
    val newPlaylist: () -> Unit,
)

@Composable
fun LibraryShell(
    currentTab: LibraryTab,
    onSelectTab: (LibraryTab) -> Unit,
    /** Simplified's library index — see [LibraryNav.libraryIndex]. */
    libraryIndex: Boolean,
    searchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onSearchClear: () -> Unit,
    actions: ShellActions,
) {
    PlayerTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                if (searchActive) {
                    SearchView(
                        searchQuery,
                        onSearchQueryChange,
                        onSearchClose,
                        onSearchClear,
                        actions,
                    )
                } else if (libraryIndex) {
                    LibraryIndex(actions)
                } else when (currentTab) {
                    LibraryTab.ALBUMS -> AlbumsTab(actions)
                    LibraryTab.SONGS -> SongsTab(actions)
                    LibraryTab.ARTISTS -> ArtistsTab(actions)
                    LibraryTab.PLAYLISTS -> PlaylistsTab(actions)
                }
            }
            Navbar(
                current = if (searchActive || libraryIndex) null else currentTab,
                onSelect = onSelectTab,
                onMore = {
                    actions.more(if (searchActive) LibraryPage.SEARCH else currentTab.page)
                },
                onSearch = actions.search,
                searchActive = searchActive,
                onNowPlaying = actions.nowPlaying,
                onBrowse = actions.browse,
            )
        }
    }
}

/**
 * Search results, with the query edited on the LP3 keyboard.
 *
 * The SDK's [com.thelightphone.sdk.ui.LightTextInputEditor] is full-screen by
 * design — it hosts the keyboard itself — so the header shows the current query
 * and tapping it reopens the editor, rather than being an inline field driving
 * the system IME.
 */
@Composable
private fun SearchView(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    onClear: () -> Unit,
    actions: ShellActions,
) {
    val library by App.library.tracks.collectAsState()
    val results = remember(query, library.size) { App.library.search(query) }
    val listState = rememberListAnchor("search")

    // The field sits at the top of the results rather than under the header, so
    // it goes away as you scroll into them — it has done its job by then. That
    // puts it inside the list's padding, which is also what lines its left edge
    // up with the artwork on every row below it.
    //
    // The list therefore keeps equal margins and the rows take the scrollbar's
    // lane back individually, rather than the field inheriting a right margin
    // several times its left one.
    val lane = px(SCROLLBAR_LANE_PX) - px(LIST_EDGE_PX)
    Column(Modifier.fillMaxSize()) {
        SearchHeader(actions)
        Box(modifier = Modifier.weight(1f)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = listPadding(end = px(LIST_EDGE_PX)),
        ) {
            item {
                SearchField(
                    query,
                    onEdit = { actions.editSearch(query) },
                    onClear = onClear,
                )
            }
            if (query.isNotBlank() && results.isEmpty) {
                item { EmptyState("No results") }
            }
            if (results.artists.isNotEmpty()) {
                item { SectionLabel("Artists") }
                items(results.artists, key = { "ar-${it.name}" }) { artist ->
                    TextRow(title = artist.name, modifier = Modifier.padding(end = lane)) {
                        onClose()
                        actions.openArtist(artist.name, Parent.tab(LibraryTab.ARTISTS))
                    }
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
                        onClick = { onClose(); actions.openAlbum(album.id, Parent.artist(album.artist)) },
                        onLongClick = { onClose(); actions.albumOptions(album.id) },
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
                            // branches above: an artist or an album is a place,
                            // and going to one lays its tab down as the parent,
                            // so the results have nowhere left to sit. Playing a
                            // track pushes the player straight onto this page —
                            // and back out of the player means back to the
                            // results you picked from, still typed, still
                            // scrolled where you left them.
                            App.playback.playQueue(listOf(track), 0)
                            actions.nowPlaying()
                        },
                        onLongClick = { actions.trackOptions(track.id, null) },
                    )
                }
            }
        }
        ListScrollBar(listState)
        }
    }
}

/**
 * The search page's header: the page's name, in the corners every page uses.
 *
 * "Search" rather than the query. The page is called search whether or not
 * anything has been typed into it, and the query has a field of its own below
 * — see [SearchField] — which says it in the place you would go to change it.
 */
@Composable
private fun SearchHeader(actions: ShellActions) {
    val simplified = App.layoutMode.collectAsState().value == LayoutMode.SIMPLIFIED
    AppHeader(
        leftAction = if (simplified) null else HeaderAction(AppIcons.Waveform, actions.nowPlaying),
        title = "Search",
        fitTitle = true,
        rightAction = HeaderAction(AppIcons.MoreHoriz) { actions.more(LibraryPage.SEARCH) },
    )
}

/**
 * The query, drawn the way the LP3's own keyboard screen draws it: a line of
 * text with a rule beneath, the width of the page.
 *
 * Tapping it reopens that keyboard rather than focusing an inline field — the
 * SDK's editor is full-screen by design, so the query is shown here and edited
 * there, and making the two look alike is what says they are the same field.
 *
 * Clearing sits at the right end, where a text field conventionally keeps it,
 * and appears only with something to clear. Emptying stays on this page:
 * leaving search is what the bar is for, and clearing used to do both, which
 * meant you could never start a new search from an old one.
 */
@Composable
private fun SearchField(query: String, onEdit: () -> Unit, onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(SEARCH_FIELD_PX))
            // The list's padding puts the item at the screen's edge margin; a
            // row then insets its own contents by this much again, which is
            // where the artwork on every row below starts. The field takes the
            // same inset so its left edge lines up with them — and the same on
            // the right, so the two margins match.
            .padding(horizontal = 1.5f.gridUnitsAsDp()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .appClickable(onClick = onEdit),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(AppIcons.Search, size = px(SEARCH_GLYPH_PX))
            Spacer(Modifier.width(n(10)))
            Box(modifier = Modifier.weight(1f)) {
                AppText(
                    text = query.ifEmpty { "Search" },
                    size = pxSp(SEARCH_TEXT_PX),
                    dim = query.isEmpty(),
                    maxLines = 1,
                )
            }
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(n(8)))
                AppIcon(
                    AppIcons.Close,
                    size = px(SEARCH_CLEAR_PX),
                    modifier = Modifier.appClickable(onClick = onClear),
                )
            }
        }
        // The rule the keyboard screen draws under its own field, at the weight
        // the app uses for every other hairline.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(px(SEARCH_RULE_PX))
                .background(LightThemeTokens.colors.content),
        )
    }
}

/**
 * The field's band, its text, and its two glyphs — in the same px units the
 * rows use, so they can be read against ROW_TITLE_PX (54) and its neighbours
 * rather than against a scale of their own.
 *
 * The text sits above a row's title: this is the thing the page is about, and
 * at the row size it read as one more entry in the list under it.
 */
private const val SEARCH_FIELD_PX = 120
private const val SEARCH_TEXT_PX = 63
private const val SEARCH_GLYPH_PX = 72
private const val SEARCH_CLEAR_PX = 66

/**
 * Heavier than the app's hairline: this line is the field's own edge, the way
 * the LP3's keyboard screen draws it, not a scrollbar's track.
 */
private const val SEARCH_RULE_PX = 6

/**
 * The header every tab shares: sort at the left corner, search and now-playing at
 * the right, and — when the setting puts it there — the liked/all switch in the
 * mirror of the search slot.
 */
@Composable
private fun TabHeader(tab: LibraryTab, actions: ShellActions) {
    // Now-playing at the left corner and More at the right, where it reads as
    // this page's own menu rather than a fifth tab. Nothing is folded into the
    // title: it used to open the sort menu, which was a gesture with nothing to
    // announce it and one of three different ways into the three things that
    // are now rows on More. The title names the page and no more than that.
    //
    // Search has gone to the tab bar, so this is two corner squares and a title
    // spanning everything between them.
    // Except under Simplified, where the bar holds the player itself: a second
    // way to the same page, one above the other, is one too many — and the
    // corner it vacates becomes the way back to the library index this tab was
    // opened from.
    val simplified = App.layoutMode.collectAsState().value == LayoutMode.SIMPLIFIED
    AppHeader(
        title = tabTitle(tab, likedOnly(tab)),
        leftAction = if (simplified) null else HeaderAction(AppIcons.Waveform, actions.nowPlaying),
        onBack = if (simplified) ({ LibraryNav.openLibraryIndex() }) else null,
        fitTitle = true,
        rightAction = HeaderAction(AppIcons.MoreHoriz) { actions.more(tab.page) },
    )
}

/** The tab, as the page whose modifiers More shows. */
val LibraryTab.page: LibraryPage
    get() = when (this) {
        LibraryTab.ALBUMS -> LibraryPage.ALBUMS
        LibraryTab.SONGS -> LibraryPage.SONGS
        LibraryTab.ARTISTS -> LibraryPage.ARTISTS
        LibraryTab.PLAYLISTS -> LibraryPage.PLAYLISTS
    }

/** Whether this tab is currently showing only liked items. */
@Composable
fun likedOnly(tab: LibraryTab): Boolean = when (tab) {
    LibraryTab.ALBUMS -> App.likedAlbumsOnly.collectAsState().value
    LibraryTab.SONGS -> App.likedSongsOnly.collectAsState().value
    LibraryTab.ARTISTS -> App.likedArtistsOnly.collectAsState().value
    // Nothing likes a playlist.
    LibraryTab.PLAYLISTS -> false
}

/**
 * What the tab is called, given the narrowing over it.
 *
 * The title is the only thing on the page that says a list has been narrowed —
 * without it a filtered library just looks like one that lost most of its
 * records.
 */
fun tabTitle(tab: LibraryTab, likedOnly: Boolean): String =
    if (likedOnly) "Liked " + tab.title else tab.title

@Composable
private fun rememberLocalAccess(): Boolean {
    val source = App.source.collectAsState().value
    val sync by App.library.syncState.collectAsState()
    var readable by remember(source.id) { mutableStateOf(true) }
    LaunchedEffect(source.id, sync.lastSyncedMs) {
        readable = source.kind != SourceKind.LOCAL || LocalLibrary.permitted()
    }
    return readable
}

/**
 * The one row a local library shows when it hasn't been let in yet.
 *
 * Without it the tab is simply empty, which reads as "this phone has no music"
 * rather than "this app hasn't been allowed to look".
 */
private fun LazyListScope.localAccessNotice(needed: Boolean, onAsk: () -> Unit) {
    if (!needed) return
    item { PlayAllRow(AppIcons.Smartphone, "Allow Music Access", onClick = onAsk) }
}

@Composable
private fun AlbumsTab(actions: ShellActions) {
    val view by App.sortedAlbums.collectAsState()
    val sorted = view.items
    val letters = view.letters
    val reversed by App.settings.albumSortReversed.collectAsState(initial = false)
    val downloadedAlbums by App.library.downloadedAlbumIds.collectAsState()
    // Offered only when the list it leads to has something in it: a switch to an
    // empty page is a dead end dressed as a destination.
    // Both read unconditionally: behind && the second collectAsState is a
    // conditional composable call, and the state it subscribes to stops
    // triggering recomposition — which is why the albums tab kept its switch
    // hidden long after the liked albums had loaded.
    val supportsLikes = App.source.collectAsState().value.supportsLikes
    val needsAccess = !rememberLocalAccess()
    val audioPermission = rememberPermissionRequestLauncher(READ_MEDIA_AUDIO)
    val grid = App.albumGrid.collectAsState().value
    Column(Modifier.fillMaxSize()) {
        TabHeader(LibraryTab.ALBUMS, actions)
        if (grid) {
            // Its own anchor, separate from the list's: see rememberGridAnchor.
            val gridState = rememberGridAnchor("tab:albums/grid", headerCount = 1)
            // No strip and no bar at either width: the covers take the whole
            // screen, which is the reason to be in a grid at all.
            Box(modifier = Modifier.weight(1f)) {
                AlbumGrid(
                    albums = sorted,
                    onOpen = { actions.openAlbum(it.id, Parent.Here) },
                    onLongPress = { actions.albumOptions(it.id) },
                    state = gridState,
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        RandomAlbumRow(sorted, actions)
                    }
                }
            }
        } else {
            IndexedList(
                anchor = "tab:albums",
                // The index only makes sense while the list is in name order.
                letters = letters,
                headerCount = 1,
                reversed = reversed,
            ) {
                localAccessNotice(needsAccess) { audioPermission?.launch() }
                item { RandomAlbumRow(sorted, actions) }
                items(sorted, key = { it.id }) { album ->
                    TrackRow(
                        title = album.title,
                        subtitle = album.artist,
                        coverArtId = album.coverArtId,
                        fallback = AppIcons.Album,
                        downloaded = album.id in downloadedAlbums,
                        onClick = { actions.openAlbum(album.id, Parent.Here) },
                        onLongClick = { actions.albumOptions(album.id) },
                    )
                }
            }
        }
    }
}

/**
 * One record off the shelf, at random.
 *
 * Shuffle's place on the song lists, but not shuffle's job: an album is a thing
 * someone sequenced, so this picks one and plays it in its own order. Obeys the
 * list as it stands, so a filter or a search narrows what can come up.
 */
@Composable
private fun RandomAlbumRow(albums: List<Album>, actions: ShellActions) {
    PlayAllRow(AppIcons.Shuffle, "Play Random Album") {
        val album = albums.randomOrNull() ?: return@PlayAllRow
        App.scope.launch {
            // Reading the album's tracks is a database call and belongs off the
            // main thread; handing them to the player is not. ExoPlayer's looper
            // *is* Main and it enforces that — see PlaybackController, where
            // every other caller happens to arrive from a composable and so is
            // already on it.
            val queue = App.library.albumQueue(listOf(album.id))
            if (queue.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                App.playback.playQueue(queue, 0)
                actions.nowPlaying()
            }
        }
    }
}

/**
 * A tab list with the A–Z jump strip down its right edge.
 *
 * [letters] is the bucket for each row (empty to hide the strip — it's meaningless
 * when the list isn't alphabetical). [headerCount] is how many rows sit above the
 * indexed content so a jump lands on the right item, and [anchor] names the list
 * so it can come back to where it was after a visit to another screen.
 */
@Composable
private fun ColumnScope.IndexedList(
    anchor: String,
    letters: List<Char>,
    headerCount: Int,
    reversed: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberListAnchor(anchor, headerCount)
    val scope = rememberCoroutineScope()
    Box(modifier = Modifier.weight(1f)) {
        val indexed = letters.isNotEmpty()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Keeps titles clear of whichever bar is down the right edge; without
            // it a long album name ran underneath the letters.
            contentPadding = listPadding(
                end = px(if (indexed) INDEX_STRIP_PX else SCROLLBAR_LANE_PX),
            ),
            content = content,
        )
        if (!indexed) ListScrollBar(listState)
        // A descending list keeps its index; the strip just reads Z→A to match.
        if (indexed) {
            AlphabetIndex(
                letters = letters,
                target = rememberScrollTarget(listState),
                scope = scope,
                headerCount = headerCount,
                reversed = reversed,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}


@Composable
private fun SongsTab(actions: ShellActions) {
    val view by App.sortedSongs.collectAsState()
    val sorted = view.items
    val letters = view.letters
    val reversed by App.settings.songSortReversed.collectAsState(initial = false)
    val downloadedIds by App.library.downloadedTrackIds.collectAsState()
    val selection = rememberSelection("songs")
    // Both read unconditionally: behind && the second collectAsState is a
    // conditional composable call, and the state it subscribes to stops
    // triggering recomposition — which is why the albums tab kept its switch
    // hidden long after the liked tracks had loaded.
    val supportsLikes = App.source.collectAsState().value.supportsLikes
    val needsAccess = !rememberLocalAccess()
    val audioPermission = rememberPermissionRequestLauncher(READ_MEDIA_AUDIO)

    Column(Modifier.fillMaxSize()) {
        if (selection.active) {
            SelectionHeader(selection) {
                actions.selectionActions(selection.pick(sorted) { it.id }, selection)
            }
        } else {
            TabHeader(LibraryTab.SONGS, actions)
        }
        IndexedList(
            anchor = "tab:songs",
            letters = letters,
            headerCount = if (selection.active) 0 else 1,
            reversed = reversed,
        ) {
            if (!selection.active) {
                localAccessNotice(needsAccess) { audioPermission?.launch() }
                item {
                    PlayAllRow(AppIcons.Shuffle, "Shuffle") {
                        App.playback.playQueue(shuffled(sorted), 0)
                        actions.nowPlaying()
                    }
                }
            }
            items(sorted, key = { it.id }) { track ->
                TrackRow(
                    title = track.title,
                    subtitle = track.artist,
                    coverArtId = track.coverArtId,
                    downloaded = track.id in downloadedIds,
                    selected = if (selection.active) track.id in selection.selected else null,
                    onClick = {
                        if (selection.active) {
                            selection.toggle(track.id)
                        } else {
                            val index = sorted.indexOfFirst { it.id == track.id }
                            App.playback.playQueue(sorted, index.coerceAtLeast(0))
                            actions.nowPlaying()
                        }
                    },
                    onLongClick = {
                        if (!selection.active) actions.trackOptions(track.id, selection)
                    },
                )
            }
        }
    }
}

@Composable
private fun ArtistsTab(actions: ShellActions) {
    val view by App.sortedArtists.collectAsState()
    val sorted = view.items
    val letters = view.letters
    val reversed by App.settings.artistSortReversed.collectAsState(initial = false)
    // Both read unconditionally: behind && the second collectAsState is a
    // conditional composable call, and the state it subscribes to stops
    // triggering recomposition — which is why the albums tab kept its switch
    // hidden long after the liked artists had loaded.
    val supportsLikes = App.source.collectAsState().value.supportsLikes
    val downloadedArtists by App.library.downloadedArtistNames.collectAsState()
    // One request for the server's own artist records, which is where their
    // pictures are — the library's artists come from track tags and have none.
    // Skipped entirely when the pictures are switched off — there is no point
    // fetching a page of artist records for images nothing is going to draw.
    val artistImages = !App.hideArtistImages.collectAsState().value
    LaunchedEffect(artistImages) { if (artistImages) App.library.primeArtistImages() }
    val needsAccess = !rememberLocalAccess()
    val audioPermission = rememberPermissionRequestLauncher(READ_MEDIA_AUDIO)
    Column(Modifier.fillMaxSize()) {
        TabHeader(LibraryTab.ARTISTS, actions)
        IndexedList(
            anchor = "tab:artists",
            letters = letters,
            headerCount = 0,
            reversed = reversed,
        ) {
            localAccessNotice(needsAccess) { audioPermission?.launch() }
            items(sorted, key = { it.name }) { artist ->
                ArtistRow(
                    name = artist.name,
                    subtitle = "${artist.albumCount} albums · ${artist.trackCount} songs",
                    downloaded = artist.name in downloadedArtists,
                    imageId = artist.imageId,
                    onClick = { actions.openArtist(artist.name, Parent.Here) },
                    onLongClick = { actions.artistOptions(artist.name) },
                )
            }
        }
    }
}

@Composable
private fun PlaylistsTab(actions: ShellActions) {
    val view by App.sortedPlaylists.collectAsState()
    val playlists = view.items
    val letters = view.letters
    val reversed by App.settings.playlistSortReversed.collectAsState(initial = false)
    val downloadedPlaylists by App.library.downloadedPlaylistIds.collectAsState()
    // getPlaylists only returns metadata, not membership — so the badge above
    // has nothing to go on until each playlist's tracks are fetched once here.
    LaunchedEffect(playlists) {
        App.library.primePlaylistTrackIds(playlists.map { it.id })
    }
    // A server that can only create a playlist with songs in it has no use for a
    // bare "New Playlist" — see MusicSource.supportsEmptyPlaylists.
    val source = App.source.collectAsState().value
    val canCreateEmpty = source.supportsEmptyPlaylists
    // Keyed on the source, not on first composition: playlists are dropped when
    // the source changes, and without a fetch tied to that change a shell that
    // stayed composed through the switch would sit on an empty list for ever.
    LaunchedEffect(source.id) { App.library.refreshPlaylists() }
    Column(Modifier.fillMaxSize()) {
        TabHeader(LibraryTab.PLAYLISTS, actions)
        IndexedList(
            anchor = "tab:playlists",
            letters = letters,
            headerCount = if (canCreateEmpty) 1 else 0,
            reversed = reversed,
        ) {
            if (canCreateEmpty) {
                item { PlayAllRow(AppIcons.Add, "New Playlist") { actions.newPlaylist() } }
            }
            items(playlists, key = { it.id }) { playlist ->
                TrackRow(
                    title = playlist.name,
                    subtitle = "",
                    coverArtId = playlist.coverArtId,
                    fallback = AppIcons.QueueMusic,
                    downloaded = playlist.id in downloadedPlaylists,
                    onClick = { actions.openPlaylist(playlist.id, playlist.name) },
                    onLongClick = { actions.playlistOptions(playlist.id, playlist.name) },
                )
            }
        }
    }
}


/**
 * Everything there is to browse, in one list.
 *
 * The simplified layout's middle button lands here. It holds the four tabs the
 * standard bar spreads along the bottom, plus the tag lists that would
 * otherwise only be reachable through More — this page is the library, so a way
 * of looking at the library belongs on it.
 *
 * Each entry leads somewhere and nothing here is a setting, so the header keeps
 * no corner actions: the player and search are in the bar below, an arm's reach
 * from anywhere.
 */
@Composable
private fun LibraryIndex(actions: ShellActions) {
    val source by App.source.collectAsState()
    val genres by App.library.genres.collectAsState()
    val composers by App.library.composers.collectAsState()
    // The liked lists appear only where there is something in them, and not at
    // all where the source has no likes — see MusicSource.supportsLikes.
    val likedArtists by App.library.likedArtists.collectAsState()
    val likedAlbums by App.library.likedAlbums.collectAsState()
    val likedTracks by App.library.likedTracks.collectAsState()
    val likes = source.supportsLikes

    // A Column of its own: the shell hands each page a Box, so a header and a
    // list dropped into it straight would be drawn one on top of the other.
    Column(modifier = Modifier.fillMaxSize()) {
    AppHeader(
        title = "Library",
        fitTitle = true,
        // The page's own menu, as on every other library page.
        rightAction = HeaderAction(AppIcons.MoreHoriz) { actions.more(LibraryPage.LIBRARY) },
    )
    ScrollableList(modifier = Modifier.fillMaxSize()) {
        // A source with no server has nowhere to keep a playlist — see
        // MusicSource.supportsPlaylists.
        if (source.supportsPlaylists) {
            item { IndexRow(LibraryTab.PLAYLISTS) }
        }
        item { IndexRow(LibraryTab.ARTISTS) }
        item { IndexRow(LibraryTab.ALBUMS) }
        item { IndexRow(LibraryTab.SONGS) }
        // The same three again, narrowed — as a block under the whole set, so
        // the plain lists stay together and read as one thing.
        if (likes && likedArtists.isNotEmpty()) {
            item { LikedRow(LibraryTab.ARTISTS) }
        }
        if (likes && likedAlbums.isNotEmpty()) {
            item { LikedRow(LibraryTab.ALBUMS) }
        }
        if (likes && likedTracks.isNotEmpty()) {
            item { LikedRow(LibraryTab.SONGS) }
        }
        // Only where the library has them, as on More: a server that doesn't
        // tag composers shouldn't offer a Composers page.
        if (composers.isNotEmpty()) {
            item {
                TextRow(
                    title = "Composers",
                    leading = { AppIcon(AppIcons.Composer, size = n(22)) },
                    onClick = actions.composers,
                )
            }
        }
        if (genres.isNotEmpty()) {
            item {
                TextRow(
                    title = "Genres",
                    leading = { AppIcon(AppIcons.Genre, size = n(22)) },
                    onClick = actions.genres,
                )
            }
        }
    }
    }
}

/** One tab, on the index: its own bar icon, and its name. */
@Composable
private fun IndexRow(tab: LibraryTab) {
    TextRow(
        title = tab.title,
        leading = { AppIcon(iconFor(tab), size = n(22)) },
        onClick = {
            // The unnarrowed list: an entry named "Albums" that showed only the
            // liked ones because the filter was left on would be lying.
            App.scope.launch { setLikedOnly(tab, false) }
            LibraryNav.selectTab(tab)
        },
    )
}

/** The same tab, narrowed to what you kept. */
@Composable
private fun LikedRow(tab: LibraryTab) {
    TextRow(
        title = "Liked ${tab.title}",
        leading = { AppIcon(AppIcons.Favorite, size = n(22)) },
        onClick = {
            App.scope.launch { setLikedOnly(tab, true) }
            LibraryNav.selectTab(tab)
        },
    )
}

private suspend fun setLikedOnly(tab: LibraryTab, value: Boolean) {
    when (tab) {
        LibraryTab.ALBUMS -> App.settings.setLikedAlbumsOnly(value)
        LibraryTab.SONGS -> App.settings.setLikedSongsOnly(value)
        LibraryTab.ARTISTS -> App.settings.setLikedArtistsOnly(value)
        LibraryTab.PLAYLISTS -> Unit
    }
}

/**
 * The bottom bar, in whichever shape the Layout setting asks for.
 *
 * Both shapes take the same handlers and each uses what it needs, so a caller
 * doesn't have to know which one is showing — see [AppSettings.layoutMode].
 */
@Composable
fun Navbar(
    current: LibraryTab?,
    onSelect: (LibraryTab) -> Unit,
    onMore: () -> Unit,
    moreActive: Boolean = false,
    /** Opens library search — the bar's fifth destination in the classic layout. */
    onSearch: (() -> Unit)? = null,
    searchActive: Boolean = false,
    /** Simplified keeps the player in the bar rather than the header's corner. */
    onNowPlaying: (() -> Unit)? = null,
    /** Simplified's centre tile: which library you are looking at. */
    onBrowse: (() -> Unit)? = null,
) {
    // Read from the eager state rather than the preference Flow: the bar is on
    // every screen, and collecting the Flow fresh each time means a frame of
    // the wrong bar before the stored answer arrives.
    val simplified = App.layoutMode.collectAsState().value == LayoutMode.SIMPLIFIED
    if (simplified && onSearch != null && onNowPlaying != null && onBrowse != null) {
        SimplifiedNavbar(onSearch, onNowPlaying, onBrowse, searchActive)
    } else {
        StandardNavbar(current, onSelect, onSearch, searchActive)
    }
}

/**
 * Simplified layout: 3-button navbar with Search, Browse (center), and Now Playing.
 */
@Composable
private fun SimplifiedNavbar(
    onSearch: () -> Unit,
    onNowPlaying: () -> Unit,
    onBrowse: (() -> Unit)?,
    searchActive: Boolean,
) {
    val artworkEdge = px(EDGE_GLYPH_PX)
    // The end glyphs line up with that edge — the left one's left edge, the
    // right one's right edge, each the same distance in. They are centred in
    // boxes of the bar's own height, so the padding is that distance less the
    // slack the glyph already has inside its box; the two differ because the
    // two glyphs are drawn at different sizes.
    val tile = px(BOTTOM_BAR_PX)
    val librarySlack = (tile - px(NAV_ICON_PX) * navScale(LIBRARY_DRAWN_PX, TALL_TARGET_PX)) / 2
    val playerSlack = (tile - px(NAV_ICON_PX) * NOW_PLAYING_SCALE) / 2
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(BOTTOM_BAR_PX))
            .padding(
                start = (artworkEdge - librarySlack).coerceAtLeast(0.dp),
                end = (artworkEdge - playerSlack).coerceAtLeast(0.dp),
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Exactly one of these is where you are. The library covers the index
        // and everything reached from it, which is everything this bar can be
        // seen over that isn't search — the player is a screen of its own and
        // hides the bar entirely, so it is never the lit one.
        //
        //
        // The library at the left, always drawn as the library. It used to show
        // whichever tab you were on with a chevron beside it, which made it a
        // control for changing that — it is a place you go instead. Brought to
        // the same rendered height as the Albums tab it stands in for.
        NavTile(
            AppIcons.LibraryMusic,
            active = !searchActive,
            scale = navScale(LIBRARY_DRAWN_PX, TALL_TARGET_PX),
            box = BOTTOM_BAR_PX,
        ) { onBrowse?.invoke() }
        Spacer(Modifier.weight(1f))
        NavTile(AppIcons.Search, active = searchActive) { onSearch() }
        Spacer(Modifier.weight(1f))
        // The player at the right: the one thing in this bar that isn't
        // somewhere to browse, and under this layout the bar is the only route
        // to it.
        NavTile(
            AppIcons.Waveform,
            active = false,
            scale = NOW_PLAYING_SCALE,
            box = BOTTOM_BAR_PX,
        ) { onNowPlaying() }
    }
}

/**
 * Standard layout: 5-button navbar with separate tabs for Playlists, Artists, Albums,
 * Songs, and Search. This is the classic light-amp navigation with all library views
 * directly accessible from the bottom bar.
 */
@Composable
private fun StandardNavbar(
    current: LibraryTab?,
    onSelect: (LibraryTab) -> Unit,
    onSearch: (() -> Unit)?,
    searchActive: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(BOTTOM_BAR_PX))
            .padding(horizontal = 0.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A source with no server has nowhere to keep a playlist, so the tab
        // isn't there to be tapped — see MusicSource.supportsPlaylists.
        if (App.source.collectAsState().value.supportsPlaylists) {
            NavIcon(
                AppIcons.QueueMusic,
                current == LibraryTab.PLAYLISTS,
                scale = PLAYLISTS_SCALE,
                lift = PLAYLISTS_LIFT_PX,
            ) { LibraryNav.selectTab(LibraryTab.PLAYLISTS) }
        }
        NavIcon(
            AppIcons.RecordVoiceOver,
            current == LibraryTab.ARTISTS,
            scale = navScale(ARTISTS_DRAWN_PX, ARTISTS_TARGET_PX),
        ) { LibraryNav.selectTab(LibraryTab.ARTISTS) }
        NavIcon(
            AppIcons.AlbumStack,
            current == LibraryTab.ALBUMS,
            scale = navScale(ALBUMS_DRAWN_PX, TALL_TARGET_PX),
        ) { LibraryNav.selectTab(LibraryTab.ALBUMS) }
        NavIcon(
            AppIcons.MusicNote,
            current == LibraryTab.SONGS,
            scale = navScale(SONGS_DRAWN_PX, TALL_TARGET_PX),
        ) { onSelect(LibraryTab.SONGS) }
        // Search is the fifth destination: the one thing here you arrive at
        // rather than browse to, so it sits after the four ways of looking at
        // the same library rather than among them.
        if (onSearch != null) {
            NavIcon(AppIcons.Search, searchActive, scale = SEARCH_SCALE) { onSearch() }
        }
    }
}


@Composable
private fun NavIcon(
    icon: ImageVector,
    active: Boolean,
    /** Trims a glyph that draws larger than the rest at the same box size. */
    scale: Float = 1f,
    /** Nudges the glyph up inside its tile, in the same px units as everything else. */
    lift: Int = 0,
    onClick: () -> Unit,
) {
    // The selected tab gets a tile behind it — the same way the queue button
    // marks itself on the player. Its colour comes from the theme, so Invert
    // Colors still works.
    Box(
        modifier = Modifier
            .size(px(NAV_TILE_PX))
            .clip(RoundedCornerShape(px(NAV_TILE_RADIUS_PX)))
            .then(
                Modifier,
            )
            .appClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            icon = icon,
            size = px(NAV_ICON_PX) * scale,
            // Inverted out of the white tile, or — with no tile — simply the
            // bright one among dimmer neighbours. The secondary grey was too
            // close to white here to read as a difference at all, so the
            // unselected glyphs carry an explicit alpha instead.
            // The tab you are on is simply the bright one; the rest step back.
            tint = LightThemeTokens.colors.content,
            modifier = (if (active) Modifier else Modifier.alpha(NAV_DIM_ALPHA))
                .offset(y = -px(lift)),
        )
    }
}

/** A bar button with no tab behind it — Simplified's search and player. */
@Composable
private fun NavTile(
    icon: ImageVector,
    /** The one you are on is the bright one; the rest step back, as in the tabs. */
    active: Boolean = false,
    /** Trims a glyph that draws larger than the rest at the same box size. */
    scale: Float = SEARCH_SCALE,
    /** The square the glyph centres in, and the size of its tap target. */
    box: Int = NAV_TILE_PX,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(px(box))
            .clip(RoundedCornerShape(px(NAV_TILE_RADIUS_PX)))
            .appClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            icon = icon,
            // The size the tabs are drawn at, so the bar reads as one row
            // whichever layout is showing. n(28) was a measure of its own and
            // came out visibly smaller than the standard bar's glyphs beside it.
            size = px(NAV_ICON_PX) * scale,
            tint = LightThemeTokens.colors.content,
            modifier = if (active) Modifier else Modifier.alpha(NAV_DIM_ALPHA),
        )
    }
}

/**
 * Standard layout tab icon with scaling and optional lift for visual alignment.
 * Used in the 5-button standard navbar layout.
 */

private const val NAV_TILE_PX = 144
private const val NAV_TILE_RADIUS_PX = 6

/** Chevron shown next to type icon only on the library root. Match top-bar chevron (n(22)). */
private const val BROWSE_CHEVRON_SIZE = 22
/** Slight shrink so icon + chevron balance inside the NAV_TILE hit area. */
private const val BROWSE_ICON_SCALE_WITH_CHEVRON = 0.92f

// --- Standard layout constants ---

/** The tile a selected tab inverts into, and the glyph inside it. */
private const val NAV_ICON_PX = 114

/** Matches the player's secondary row — the app's one "present but not chosen". */
private const val NAV_DIM_ALPHA = 0.45f

/**
 * Bringing the tab glyphs closer to one height.
 *
 * A Material icon fills its 24dp viewport differently depending on its shape —
 * a mic is tall and narrow, a playlist is wide and short, a pair of sleeves goes
 * corner to corner — so one box size draws wildly different things. At the
 * shared box the bar ran from 66px (playlists) to 90px (artists), which read as
 * four icons in three sizes rather than as one row.
 *
 * `*_DRAWN_PX` is what each glyph actually drew at that shared box, measured off
 * the panel; the target is what it should draw instead. Re-measure rather than
 * guess if a glyph changes.
 *
 * The playlist glyph is the reference and is left alone — the tall ones were
 * what read wrong, and growing the smallest to meet them halfway only moved the
 * problem onto it.
 */
private const val ARTISTS_DRAWN_PX = 90
// Measured at 89 while the old 0.85 trim was still applied, so untrimmed it is
// 89 / 0.85 — the others were measured with no scale on them at all.
private const val ALBUMS_DRAWN_PX = 105
private const val SONGS_DRAWN_PX = 86

/**
 * The simplified bar's library glyph, which stands in for the Albums tab.
 *
 * Its own measure, not the album stack's: the stack is two offset squares that
 * fill the box and needs the heaviest trim of the set, and lending that trim to
 * a glyph that doesn't over-draw the same way left this one visibly small. An
 * estimate rather than a panel measurement — re-measure if it still reads off.
 */
private const val LIBRARY_DRAWN_PX = 95

/** Where the tall glyphs land: above the playlist's own 66, well under their 90. */
private const val TALL_TARGET_PX = 72

/** A mic is the narrowest of them, so it carries a little more height than the rest. */
private const val ARTISTS_TARGET_PX = 75

private fun navScale(drawnPx: Int, targetPx: Int): Float = targetPx.toFloat() / drawnPx

/**
 * How far the outer edge of a glyph sits from the screen's edge, for the bars
 * that stand against them — the simplified nav bar, and the queue's shuffle and
 * repeat. A pixel inside where a list row's artwork begins (LIST_EDGE_PX plus a
 * row's 1.5-unit inset comes to 81), taken to the round number, which is also
 * half the bar's height.
 */
const val EDGE_GLYPH_PX = 80

/** A shade off the reference size, and a shade higher in the tile. Judged by eye. */
private const val PLAYLISTS_SCALE = 0.94f
private const val PLAYLISTS_LIFT_PX = 5



/** The magnifier, at the same reference size as the rest of the bar. */
private const val SEARCH_SCALE = PLAYLISTS_SCALE

/** The waveform, a shade under the rest — it drew heavy beside them. */
private const val NOW_PLAYING_SCALE = 0.87f


