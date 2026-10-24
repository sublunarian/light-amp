package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.sublunar.amp.App
import com.sublunar.amp.data.Album
import com.sublunar.amp.data.LayoutMode
import com.sublunar.amp.data.SyncState
import com.sublunar.amp.data.TagFilter
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.shuffled
import com.sublunar.amp.source.PhoneAccess
import com.sublunar.amp.source.PhoneLibrary
import com.sublunar.amp.ui.PlayerTheme
import com.sublunar.amp.ui.components.AlbumGrid
import com.sublunar.amp.ui.components.AlphabetIndex
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.ArtistRow
import com.sublunar.amp.ui.components.CORNER_ICON_PX
import com.sublunar.amp.ui.components.EmptyState
import com.sublunar.amp.ui.components.HEADER_BAR_PX
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.INDEX_STRIP_PX
import com.sublunar.amp.ui.components.LIST_EDGE_PX
import com.sublunar.amp.ui.components.LatestList
import com.sublunar.amp.ui.components.ListScrollBar
import com.sublunar.amp.ui.components.PlayAllRow
import com.sublunar.amp.ui.components.ROW_ACTION_ICON_PX
import com.sublunar.amp.ui.components.ROW_GAP_PX
import com.sublunar.amp.ui.components.ROW_LEAD_PX
import com.sublunar.amp.ui.components.ROW_TITLE_LINE_PX
import com.sublunar.amp.ui.components.ROW_TITLE_PX
import com.sublunar.amp.ui.components.SCROLLBAR_LANE_PX
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.SelectionHeader
import com.sublunar.amp.ui.components.SelectionState
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.components.TrackRow
import com.sublunar.amp.ui.components.appClickable
import com.sublunar.amp.ui.components.listPadding
import com.sublunar.amp.ui.components.rememberGridAnchor
import com.sublunar.amp.ui.components.rememberLatest
import com.sublunar.amp.ui.components.rememberListAnchor
import com.sublunar.amp.ui.components.rememberScrollTarget
import com.sublunar.amp.ui.components.rememberSelection
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** The SDK's full-screen editor — Simplified's way of typing a query. */
    val editSearch: (String) -> Unit,
    /** More carries the showing page's modifiers up with it — see [LibraryPage]. */
    val more: (LibraryPage) -> Unit,
    /** Simplified's library button — see [LibraryNav.pressLibrary]. */
    val browse: () -> Unit,
    /** Each takes where back should land — see [Parent]. */
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
                        currentTab,
                        actions,
                    )
                } else if (libraryIndex) {
                    LibraryIndex(actions)
                } else {
                    TabContent(currentTab, actions)
                }
            }
            // The keyboard takes the bottom of the screen while typing, and the bar
            // would be a second row of controls under it. Return brings it back.
            if (!LibraryNav.typing.collectAsState().value) {
                Navbar(
                    // Search keeps the tab it was started from lit: it borrows the
                    // list in front of you and hands it back on the way out.
                    current = if (libraryIndex) null else currentTab,
                    onSelect = onSelectTab,
                    onSearch = actions.search,
                    searchActive = searchActive,
                    onNowPlaying = actions.nowPlaying,
                    onBrowse = actions.browse,
                )
            }
        }
    }
}

/**
 * One of the four tab lists, with or without the header it normally carries.
 *
 * Search borrows the body without the header — see [SearchView].
 */
@Composable
private fun TabContent(tab: LibraryTab, actions: ShellActions, header: Boolean = true) {
    when (tab) {
        LibraryTab.ALBUMS -> AlbumsTab(actions, header)
        LibraryTab.SONGS -> SongsTab(actions, header)
        LibraryTab.ARTISTS -> ArtistsTab(actions, header)
        LibraryTab.PLAYLISTS -> PlaylistsTab(actions, header)
    }
}

@Composable
private fun SearchView(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    /** The list search was started from — what it shows until something is typed. */
    tab: LibraryTab,
    actions: ShellActions,
) {
    val typing by LibraryNav.typing.collectAsState()
    // While the keyboard is up the field is the query: the keyboard writes into
    // this state and the results follow it keystroke by keystroke.
    val state = rememberTextFieldState(query)
    val live = if (typing) state.text.toString() else query
    LaunchedEffect(live, typing) { if (typing) onQueryChange(live) }

    val library by App.library.tracks.collectAsState()
    val results = remember(live, library.size) { App.library.search(live) }
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
                    actions.editSearch(live)
                } else {
                    LibraryNav.typing.value = true
                }
            },
            onCancel = {
                // The cross abandons the search, query included: back to the
                // list it was started from, as if it never happened.
                state.clearText()
                onQueryChange("")
                LibraryNav.typing.value = false
                onClose()
            },
            onMenu = { actions.more(LibraryPage.SEARCH) },
        )
        Box(modifier = Modifier.weight(1f)) {
            // Nothing typed yet, so the list that was already there: the keyboard
            // opening is not itself a search.
            if (live.isBlank()) {
                TabContent(tab, actions, header = false)
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
                                subtitle = "",
                                imageId = artist.imageId,
                                modifier = Modifier.padding(end = lane),
                                onClick = {
                                    onClose()
                                    actions.openArtist(artist.name, Parent.tab(LibraryTab.ARTISTS))
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
                                    // branches above, which lay a tab down as the
                                    // parent: back out of the player lands on the
                                    // results, still typed and scrolled.
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
                .width(px(SEARCH_SLOT_PX))
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

/** AppHeader's inner slot, which the cross stands in. */
private const val SEARCH_SLOT_PX = 80

/** Where the search glyph sits: the list's edge plus a row's lead. */
private const val SEARCH_AXIS_PX = LIST_EDGE_PX + ROW_LEAD_PX

/** The search header's type and glyph, in the rows' sizes so it matches the rows beneath. */
private const val SEARCH_TEXT_PX = ROW_TITLE_PX
private const val SEARCH_GLYPH_PX = ROW_ACTION_ICON_PX

/**
 * The header every tab shares: the page's name, More at the right corner (held,
 * Settings), and search beside it. Under Simplified, where the bar holds search,
 * the left corner is the way back to the library index instead.
 */
@Composable
private fun TabHeader(tab: LibraryTab, actions: ShellActions) {
    val simplified = App.settings.layoutMode.state.collectAsState().value == LayoutMode.SIMPLIFIED
    AppHeader(
        title = tabTitle(tab, likedOnly(tab), tagFilter(tab)),
        onBack = if (simplified) ({ LibraryNav.openLibraryIndex() }) else null,
        // Beside the page's menu, as a way of narrowing the list like the sort
        // and filters. Straight to the keyboard: a magnifier is a request to type.
        searchAction = if (simplified) {
            null
        } else {
            ({ LibraryNav.openSearch(withKeyboard = true) })
        },
        fitTitle = true,
        rightAction = HeaderAction(AppIcons.Sort, onLongClick = actions.settings) { actions.more(tab.page) },
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

/**
 * The genre and composer a tab has been narrowed to — see [TagFilterScreen].
 *
 * Only the two lists of things a tag describes have one: an artist is not a
 * genre's or a composer's, and a playlist is whatever was put in it.
 */
@Composable
fun tagFilter(tab: LibraryTab): TagFilter = when (tab) {
    LibraryTab.ALBUMS -> App.albumsTagFilter.collectAsState().value
    LibraryTab.SONGS -> App.songsTagFilter.collectAsState().value
    else -> TagFilter()
}

/** Whether this tab is currently showing only liked items. */
@Composable
fun likedOnly(tab: LibraryTab): Boolean = when (tab) {
    LibraryTab.ALBUMS -> App.settings.likedAlbumsOnly.state.collectAsState().value
    LibraryTab.SONGS -> App.settings.likedSongsOnly.state.collectAsState().value
    LibraryTab.ARTISTS -> App.settings.likedArtistsOnly.state.collectAsState().value
    // Nothing likes a playlist.
    LibraryTab.PLAYLISTS -> false
}

/**
 * What the tab is called, given the narrowing over it (liked, genre, composer):
 * the title is the only thing on the page that says a list has been narrowed.
 */
fun tabTitle(tab: LibraryTab, likedOnly: Boolean, filter: TagFilter = TagFilter()): String {
    val base = if (likedOnly) "Liked " + tab.title else tab.title
    val tags = listOf(filter.genre, filter.composer).filter { it.isNotEmpty() }
    return if (tags.isEmpty()) base else (listOf(base) + tags).joinToString(" · ")
}

/** Whether the active source's music can be read: false only for a phone library not let in yet. */
@Composable
private fun rememberLocalAccess(): Boolean {
    val source = App.source.collectAsState().value
    val sync by App.librarySync.syncState.collectAsState()
    var readable by remember(source.id) { mutableStateOf(true) }
    LaunchedEffect(source.id, sync.lastSyncedMs) {
        readable = source.phoneLibrary?.let { it.access() == PhoneAccess.GRANTED } ?: true
    }
    return readable
}

/**
 * Asks LightOS to let this tool read [phone]'s folder — see lighttool.toml.
 * Does nothing for a source with a server.
 *
 * The launcher is made whatever the source, so this is never a conditional
 * composable call — see ARCHITECTURE.md.
 */
@Composable
internal fun rememberAccessRequest(phone: PhoneLibrary?): () -> Unit {
    val launcher = rememberPermissionRequestLauncher(phone?.permission.orEmpty())
    return { if (phone != null) launcher?.launch() }
}

/**
 * The one row a local library shows when it hasn't been let in yet: without it
 * the tab is simply empty, which reads as "this phone has no music".
 */
private fun LazyListScope.localAccessNotice(needed: Boolean, onAsk: () -> Unit) {
    if (!needed) return
    item { PlayAllRow(AppIcons.Smartphone, "Allow Music Access", onClick = onAsk) }
}

/**
 * Why the last sync gave up, at the top of the list it left behind. A refused
 * login leaves the cached rows in place, so this may sit above a library that
 * looks fine and is no longer being updated. Tapping goes to Settings, the way
 * to the source that needs fixing.
 */
private fun LazyListScope.syncErrorNotice(error: String?, onOpen: () -> Unit) {
    if (error.isNullOrBlank()) return
    item { PlayAllRow(AppIcons.CloudOff, error, onClick = onOpen) }
}

/**
 * What the sync is doing, where the list it is filling would be: a first sync
 * of a large library takes minutes. Only while the list is empty.
 */
private fun LazyListScope.syncProgressNotice(empty: Boolean, sync: SyncState) {
    if (!empty || !sync.syncing) return
    item { SectionLabel(syncProgressText(sync.phase)) }
}

private val ALBUMS_PHASE = Regex("""Albums (\d+)/(\d+)""")

/** [SyncState.phase] in words — "Albums 120/1419" reads "Syncing… 120 of 1,419 albums". */
internal fun syncProgressText(phase: String): String {
    val counted = ALBUMS_PHASE.matchEntire(phase)
    if (counted != null) {
        val (done, total) = counted.destructured
        return "Syncing… %,d of %,d albums".format(done.toInt(), total.toInt())
    }
    return if (phase.isBlank()) "Syncing…" else "Syncing… ${phase.replaceFirstChar { it.lowercase() }}"
}

@Composable
private fun AlbumsTab(actions: ShellActions, header: Boolean = true) {
    val view by App.sortedAlbums.collectAsState()
    val sorted = view.items
    val rows = rememberLatest(sorted)
    val letters = view.letters
    val reversed by App.settings.albumSortReversed.state.collectAsState()
    val downloadedAlbums by App.library.downloadedAlbumIds.collectAsState()
    val needsAccess = !rememberLocalAccess()
    val sync = App.librarySync.syncState.collectAsState().value
    val syncError = sync.error
    val askAccess = rememberAccessRequest(App.source.collectAsState().value.phoneLibrary)
    val grid = App.albumGrid.collectAsState().value
    Column(Modifier.fillMaxSize()) {
        if (header) TabHeader(LibraryTab.ALBUMS, actions)
        if (grid) {
            // Its own anchor, separate from the list's: see rememberGridAnchor.
            val gridState = rememberGridAnchor("tab:albums/grid", headerCount = 1)
            // No strip and no bar: the covers take the whole screen.
            Box(modifier = Modifier.weight(1f)) {
                AlbumGrid(
                    albums = sorted,
                    onOpen = { actions.openAlbum(it.id, Parent.Here) },
                    onLongPress = { actions.albumOptions(it.id) },
                    state = gridState,
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        RandomAlbumRow(rows, actions)
                    }
                    if (sorted.isEmpty() && sync.syncing) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionLabel(syncProgressText(sync.phase))
                        }
                    }
                }
            }
        } else {
            IndexedList(
                anchor = "tab:albums",
                // The index only makes sense while the list is in name order.
                letters = letters,
                // The action row, so the A–Z strip lands on the right album.
                // Counted with or without the page's header: search borrows the
                // list, row and all.
                headerCount = 1,
                reversed = reversed,
            ) {
                item { RandomAlbumRow(rows, actions) }
                localAccessNotice(needsAccess, askAccess)
                syncErrorNotice(syncError, actions.settings)
                syncProgressNotice(sorted.isEmpty(), sync)
                items(sorted.size, key = { rows[it]?.id ?: it }) { i ->
                    val album = rows[i] ?: return@items
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
 * One record off the shelf, at random, played in its own order: an album is a
 * thing someone sequenced. Picks from the list as it stands, so a filter or a
 * search narrows what can come up.
 */
@Composable
private fun RandomAlbumRow(albums: LatestList<Album>, actions: ShellActions) {
    PlayAllRow(AppIcons.Shuffle, "Play Random Album") {
        val album = albums.items.randomOrNull() ?: return@PlayAllRow
        App.scope.launch {
            // The database read off Main, the player call on it: the player's
            // looper is Main — see PlaybackController.
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
private fun SongsTab(actions: ShellActions, header: Boolean = true) {
    val view by App.sortedSongs.collectAsState()
    val sorted = view.items
    val rows = rememberLatest(sorted)
    val letters = view.letters
    val reversed by App.settings.songSortReversed.state.collectAsState()
    val downloadedIds by App.library.downloadedTrackIds.collectAsState()
    val selection = rememberSelection("songs")
    val needsAccess = !rememberLocalAccess()
    val sync = App.librarySync.syncState.collectAsState().value
    val syncError = sync.error
    val askAccess = rememberAccessRequest(App.source.collectAsState().value.phoneLibrary)

    Column(Modifier.fillMaxSize()) {
        if (header) {
            if (selection.active) {
                SelectionHeader(selection) {
                    actions.selectionActions(selection.pick(rows.items) { it.id }, selection)
                }
            } else {
                TabHeader(LibraryTab.SONGS, actions)
            }
        }
        IndexedList(
            anchor = "tab:songs",
            letters = letters,
            headerCount = if (selection.active) 0 else 1,
            reversed = reversed,
        ) {
            if (!selection.active) {
                item {
                    PlayAllRow(AppIcons.Shuffle, "Shuffle") {
                        App.playback.playQueue(shuffled(rows.items), 0)
                        actions.nowPlaying()
                    }
                }
                localAccessNotice(needsAccess, askAccess)
                syncErrorNotice(syncError, actions.settings)
                syncProgressNotice(sorted.isEmpty(), sync)
            }
            // Read through [rows], never captured — see LatestList.
            items(sorted.size, key = { rows[it]?.id ?: it }) { i ->
                val track = rows[i] ?: return@items
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
                            val list = rows.items
                            val index = list.indexOfFirst { it.id == track.id }
                            App.playback.playQueue(list, index.coerceAtLeast(0))
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
private fun ArtistsTab(actions: ShellActions, header: Boolean = true) {
    val view by App.sortedArtists.collectAsState()
    val sorted = view.items
    val rows = rememberLatest(sorted)
    val letters = view.letters
    val reversed by App.settings.artistSortReversed.state.collectAsState()
    val downloadedArtists by App.library.downloadedArtistNames.collectAsState()
    // One request for the server's own artist records, which is where their
    // pictures are: the library's artists come from track tags and have none.
    // Skipped while the pictures are switched off.
    val artistImages = !App.hideArtistImages.collectAsState().value
    LaunchedEffect(artistImages) { if (artistImages) App.library.primeArtistImages() }
    val needsAccess = !rememberLocalAccess()
    val sync = App.librarySync.syncState.collectAsState().value
    val askAccess = rememberAccessRequest(App.source.collectAsState().value.phoneLibrary)
    Column(Modifier.fillMaxSize()) {
        if (header) TabHeader(LibraryTab.ARTISTS, actions)
        IndexedList(
            anchor = "tab:artists",
            letters = letters,
            headerCount = 0,
            reversed = reversed,
        ) {
            localAccessNotice(needsAccess, askAccess)
            syncProgressNotice(sorted.isEmpty(), sync)
            items(sorted.size, key = { rows[it]?.name ?: it }) { i ->
                val artist = rows[i] ?: return@items
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
private fun PlaylistsTab(actions: ShellActions, header: Boolean = true) {
    val view by App.sortedPlaylists.collectAsState()
    val playlists = view.items
    val letters = view.letters
    val reversed by App.settings.playlistSortReversed.state.collectAsState()
    val downloadedPlaylists by App.playlists.downloadedPlaylistIds.collectAsState()
    // The playlist list carries no membership, so the downloaded badge has
    // nothing to go on until each playlist's tracks are fetched once. Keyed on
    // the link as well: on a metered connection the priming is put off (see
    // primePlaylistTrackIds), and Wi-Fi arriving is the moment to do it.
    val bulkAllowed = App.rules.collectAsState().value.mayMoveHeavyBytes
    // All of them, not only those listed: a playlist left out by its stored songs is re-read here too.
    val every by App.playlists.allPlaylists.collectAsState()
    LaunchedEffect(every, bulkAllowed) {
        App.playlists.primePlaylistTrackIds(every.map { it.id })
    }
    val source = App.source.collectAsState().value
    val canCreateEmpty = source.supportsEmptyPlaylists
    // Keyed on the source: playlists are dropped when it changes, and a shell
    // that stayed composed through the switch would otherwise sit on an empty
    // list for ever.
    LaunchedEffect(source.id) { App.playlists.refreshPlaylists() }
    Column(Modifier.fillMaxSize()) {
        if (header) TabHeader(LibraryTab.PLAYLISTS, actions)
        IndexedList(
            anchor = "tab:playlists",
            letters = letters,
            headerCount = if (canCreateEmpty) 1 else 0,
            reversed = reversed,
        ) {
            // A server that can't hold an empty playlist has no use for a bare
            // "New Playlist" — see MusicSource.supportsEmptyPlaylists.
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
 * Everything there is to browse, in one list: the simplified layout's library
 * button lands here. The four tabs, then their liked versions. The player and
 * search are in the bar below.
 */
@Composable
private fun LibraryIndex(actions: ShellActions) {
    val source by App.source.collectAsState()
    // The liked lists appear only where there is something in them, and not at
    // all where the source has no likes — see MusicSource.supportsLikes.
    val likedArtists by App.library.hasLikedArtists.collectAsState()
    val likedAlbums by App.library.hasLikedAlbums.collectAsState()
    val likedTracks by App.library.hasLikedTracks.collectAsState()
    val likes = source.supportsLikes

    // A Column of its own: the shell hands each page a Box.
    Column(modifier = Modifier.fillMaxSize()) {
        AppHeader(
            title = "Library",
            fitTitle = true,
            rightAction = HeaderAction(AppIcons.Sort, onLongClick = actions.settings) {
                actions.more(LibraryPage.LIBRARY)
            },
        )
        ScrollableList(modifier = Modifier.fillMaxSize()) {
            item { IndexRow(LibraryTab.PLAYLISTS) }
            item { IndexRow(LibraryTab.ARTISTS) }
            item { IndexRow(LibraryTab.ALBUMS) }
            item { IndexRow(LibraryTab.SONGS) }
            // The same three again, narrowed, as a block under the plain lists.
            if (likes && likedArtists) {
                item { LikedRow(LibraryTab.ARTISTS) }
            }
            if (likes && likedAlbums) {
                item { LikedRow(LibraryTab.ALBUMS) }
            }
            if (likes && likedTracks) {
                item { LikedRow(LibraryTab.SONGS) }
            }
        }
    }
}

/** One tab, on the index: its own bar icon, and its name. */
@Composable
private fun IndexRow(tab: LibraryTab) {
    TextRow(
        title = tab.title,
        leading = { AppIcon(iconFor(tab), size = px(56)) },
        onClick = {
            // The unnarrowed list: "Albums" showing only the liked ones would lie.
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
        leading = { AppIcon(AppIcons.Favorite, size = px(56)) },
        onClick = {
            App.scope.launch { setLikedOnly(tab, true) }
            LibraryNav.selectTab(tab)
        },
    )
}

suspend fun setLikedOnly(tab: LibraryTab, value: Boolean) {
    when (tab) {
        LibraryTab.ALBUMS -> App.settings.likedAlbumsOnly.set(value)
        LibraryTab.SONGS -> App.settings.likedSongsOnly.set(value)
        LibraryTab.ARTISTS -> App.settings.likedArtistsOnly.set(value)
        LibraryTab.PLAYLISTS -> Unit
    }
}

/**
 * The bottom bar, in whichever shape the Layout setting asks for. Both shapes
 * take the same handlers and each uses what it needs, so a caller doesn't have
 * to know which one is showing.
 */
@Composable
fun Navbar(
    current: LibraryTab?,
    /**
     * Every tab goes through here, never LibraryNav directly: from a sub-page,
     * choosing a tab has to unwind the stack as well, and only the caller knows.
     */
    onSelect: (LibraryTab) -> Unit,
    onSearch: () -> Unit,
    searchActive: Boolean = false,
    onNowPlaying: () -> Unit,
    /** Simplified's library button — see [LibraryNav.libraryIndex]. */
    onBrowse: () -> Unit,
) {
    // The eager state rather than the preference Flow: the bar is on every
    // screen, and a fresh collect draws a frame of the wrong bar.
    val simplified = App.settings.layoutMode.state.collectAsState().value == LayoutMode.SIMPLIFIED
    if (simplified) {
        SimplifiedNavbar(onSearch, onNowPlaying, onBrowse, searchActive)
    } else {
        StandardNavbar(current, onSelect, onNowPlaying)
    }
}

/** Simplified layout: the library, search and the player, as the phone's own three buttons. */
@Composable
private fun SimplifiedNavbar(
    onSearch: () -> Unit,
    onNowPlaying: () -> Unit,
    onBrowse: () -> Unit,
    searchActive: Boolean,
) {
    val artworkEdge = px(EDGE_GLYPH_PX)
    // The end glyphs' outer edges line up with that edge. They are centred in
    // boxes of the bar's own height, so the padding is that distance less the
    // slack the glyph has inside its box, which differs with the glyph's size.
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
        // Exactly one of the two browsing buttons is lit. The library covers the
        // index and everything reached from it. The player hides the bar, so it
        // is never the lit one.
        NavTile(
            AppIcons.LibraryMusic,
            active = !searchActive,
            scale = navScale(LIBRARY_DRAWN_PX, TALL_TARGET_PX),
            box = BOTTOM_BAR_PX,
        ) { onBrowse() }
        Spacer(Modifier.weight(1f))
        NavTile(AppIcons.Search, active = searchActive) { onSearch() }
        Spacer(Modifier.weight(1f))
        NavTile(
            AppIcons.Waveform,
            active = false,
            scale = NOW_PLAYING_SCALE,
            box = BOTTOM_BAR_PX,
        ) { onNowPlaying() }
    }
}

/** Standard layout: the four tabs across the bar, then the player. */
@Composable
private fun StandardNavbar(
    current: LibraryTab?,
    onSelect: (LibraryTab) -> Unit,
    onNowPlaying: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(BOTTOM_BAR_PX)),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavIcon(
            AppIcons.QueueMusic,
            current == LibraryTab.PLAYLISTS,
            scale = PLAYLISTS_SCALE,
            lift = PLAYLISTS_LIFT_PX,
        ) { onSelect(LibraryTab.PLAYLISTS) }
        NavIcon(
            AppIcons.RecordVoiceOver,
            current == LibraryTab.ARTISTS,
            scale = navScale(ARTISTS_DRAWN_PX, ARTISTS_TARGET_PX),
        ) { onSelect(LibraryTab.ARTISTS) }
        NavIcon(
            AppIcons.AlbumStack,
            current == LibraryTab.ALBUMS,
            scale = navScale(ALBUMS_DRAWN_PX, TALL_TARGET_PX),
        ) { onSelect(LibraryTab.ALBUMS) }
        NavIcon(
            AppIcons.MusicNote,
            current == LibraryTab.SONGS,
            scale = navScale(SONGS_DRAWN_PX, TALL_TARGET_PX),
        ) { onSelect(LibraryTab.SONGS) }
        // The player after the four ways of looking at the library: the one
        // thing here you arrive at rather than browse to. Never lit, since it
        // hides this bar.
        NavIcon(AppIcons.Waveform, active = false, scale = NOW_PLAYING_SCALE) { onNowPlaying() }
    }
}

@Composable
private fun NavIcon(
    icon: ImageVector,
    /** The tab you are on is the bright one; the rest step back. */
    active: Boolean,
    /** Trims a glyph that draws larger than the rest at the same box size. */
    scale: Float = 1f,
    /** Nudges the glyph up inside its box. */
    lift: Int = 0,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(px(NAV_BOX_PX))
            .appClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            icon = icon,
            size = px(NAV_ICON_PX) * scale,
            tint = LightThemeTokens.colors.content,
            modifier = (if (active) Modifier else Modifier.alpha(NAV_DIM_ALPHA))
                .offset(y = -px(lift)),
        )
    }
}

/** A bar button with no tab behind it: Simplified's three. */
@Composable
private fun NavTile(
    icon: ImageVector,
    /** The one you are on is the bright one; the rest step back, as in the tabs. */
    active: Boolean = false,
    /** Trims a glyph that draws larger than the rest at the same box size. */
    scale: Float = SEARCH_SCALE,
    /** The square the glyph centres in, and the size of its tap target. */
    box: Int = NAV_BOX_PX,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(px(box))
            .appClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            icon = icon,
            // The size the tabs are drawn at, so the bar reads as one row
            // whichever layout is showing.
            size = px(NAV_ICON_PX) * scale,
            tint = LightThemeTokens.colors.content,
            modifier = if (active) Modifier else Modifier.alpha(NAV_DIM_ALPHA),
        )
    }
}

/** Height of the library's bottom bar: 4 grid units, as the stock LightOS bar. */
private const val BOTTOM_BAR_PX = 160

/** A bar button's square, and its tap target. */
private const val NAV_BOX_PX = 144

/** The glyph box every bar icon is scaled from. */
private const val NAV_ICON_PX = 114

/** Matches the player's secondary row: the app's one "present but not chosen". */
private const val NAV_DIM_ALPHA = 0.45f

/**
 * Bringing the tab glyphs closer to one height.
 *
 * A Material icon fills its 24dp viewport differently depending on its shape, so
 * at one box size the bar ran from 66px (playlists) to 90px (artists): four icons
 * in three sizes. `*_DRAWN_PX` is what each glyph drew at the shared box, measured
 * off the panel, and the target is what it should draw instead. Re-measure rather
 * than guess if a glyph changes. The playlist glyph is the reference.
 */
private const val ARTISTS_DRAWN_PX = 90
private const val ALBUMS_DRAWN_PX = 105 // 89 measured under a 0.85 trim, so 89 / 0.85
private const val SONGS_DRAWN_PX = 86

/**
 * The simplified bar's library glyph, which stands in for the Albums tab. Its own
 * measure, not the album stack's heavier trim. An estimate, not a panel
 * measurement: re-measure if it reads off.
 */
private const val LIBRARY_DRAWN_PX = 95

/** Where the tall glyphs land: above the playlist's own 66, well under their 90. */
private const val TALL_TARGET_PX = 72

/** A mic is the narrowest of them, so it carries a little more height than the rest. */
private const val ARTISTS_TARGET_PX = 75

private fun navScale(drawnPx: Int, targetPx: Int): Float = targetPx.toFloat() / drawnPx

/**
 * How far the outer edge of a glyph sits from the screen's edge, for the bars
 * that stand against them: the simplified nav bar, and the queue's shuffle and
 * repeat. About where a list row's artwork begins, and half the bar's height.
 */
const val EDGE_GLYPH_PX = 80

/** A shade off the reference size, and a shade higher in its box. Judged by eye. */
private const val PLAYLISTS_SCALE = 0.94f
private const val PLAYLISTS_LIFT_PX = 5

/** The magnifier, at the same reference size as the rest of the bar. */
private const val SEARCH_SCALE = PLAYLISTS_SCALE

/**
 * The waveform at the 80px box LightOS itself gives it, two grid units: 2px to
 * each unit of its 40-unit viewport, so its bars land on whole pixels. A size
 * rather than an equalisation like the tab scales above, so the scale falls out
 * of it.
 */
private const val NOW_PLAYING_PX = 80
private val NOW_PLAYING_SCALE = NOW_PLAYING_PX.toFloat() / NAV_ICON_PX
