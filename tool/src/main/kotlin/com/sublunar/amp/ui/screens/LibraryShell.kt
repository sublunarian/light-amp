package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.sublunar.amp.App
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import kotlinx.coroutines.Dispatchers
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
import com.sublunar.amp.ui.components.ListScrollBar
import com.sublunar.amp.ui.components.SCROLLBAR_LANE_PX
import com.sublunar.amp.ui.components.rememberScrollTarget
import androidx.compose.foundation.lazy.grid.GridItemSpan
import com.sublunar.amp.ui.components.AlbumGrid
import com.sublunar.amp.data.Album
import com.sublunar.amp.ui.components.PlayAllRow
import com.sublunar.amp.ui.components.SplitActionRow
import com.sublunar.amp.data.LocalLibrary
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
import com.thelightphone.sdk.ui.LightThemeTokens
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
    val more: () -> Unit,
    val browse: () -> Unit,
    /**
     * Each takes the page back should land on — a tab list is the parent of what
     * it opens, while a search result is a jump and names the hierarchy it
     * belongs to instead of stacking on top of the results. See [Parent].
     */
    val openAlbum: (String, Parent) -> Unit,
    val openArtist: (String, Parent) -> Unit,
    val openPlaylist: (String, String) -> Unit,
    val albumsSort: () -> Unit,
    /** The album lists' own list-or-grid menu, opened from the title. */
    val albumView: () -> Unit,
    val songsSort: () -> Unit,
    val artistsSort: () -> Unit,
    val playlistsSort: () -> Unit,
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
                } else when (currentTab) {
                    LibraryTab.ALBUMS -> AlbumsTab(actions)
                    LibraryTab.SONGS -> SongsTab(actions)
                    LibraryTab.ARTISTS -> ArtistsTab(actions)
                    LibraryTab.PLAYLISTS -> PlaylistsTab(actions)
                }
            }
            Navbar(
                current = if (searchActive) null else currentTab,
                onSearch = actions.search,
                onNowPlaying = actions.nowPlaying,
                onBrowse = actions.browse,
                showChevron = !searchActive,
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

    Column(Modifier.fillMaxSize()) {
        SearchHeader(query, onEdit = { actions.editSearch(query) }, onClear = onClear)
        Box(modifier = Modifier.weight(1f)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = listPadding(end = px(SCROLLBAR_LANE_PX)),
        ) {
            if (query.isNotBlank() && results.isEmpty) {
                item { EmptyState("No results") }
            }
            if (results.artists.isNotEmpty()) {
                item { SectionLabel("Artists") }
                items(results.artists, key = { "ar-${it.name}" }) { artist ->
                    TextRow(title = artist.name) {
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
                        onClick = {
                            App.playback.playQueue(listOf(track), 0)
                            onClose()
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

@Composable
private fun SearchHeader(
    query: String,
    onEdit: () -> Unit,
    onClear: () -> Unit,
) {
    // Same fixed 160px as AppHeader, so swapping into search doesn't shift the list.
    val headerHeight = px(HEADER_BAR_PX)

    Row(
        modifier = Modifier.fillMaxWidth().height(headerHeight).padding(horizontal = n(16)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(AppIcons.Search, size = n(24))
        Spacer(Modifier.width(n(12)))
        // Tapping reopens the LP3 keyboard rather than focusing an inline field:
        // the SDK's editor is full-screen by design, so the query is shown here
        // and edited there.
        Box(modifier = Modifier.weight(1f).appClickable(onClick = onEdit)) {
            if (query.isEmpty()) {
                AppText("Search", nSp(20), dim = true)
            } else {
                AppText(query, nSp(20), maxLines = 1)
            }
        }
        Spacer(Modifier.width(n(12)))
        // Empties the field and stays here. Leaving search is what the tabs are
        // for; clearing used to do both, which meant you could never simply start
        // a new search from an old one.
        AppIcon(AppIcons.Close, size = n(24), modifier = Modifier.appClickable(onClick = onClear))
    }
}

/**
 * The header every tab shares: sort folded into the title (with chevron when
 * applicable), search, and More at the right. Now-playing moved to bottom bar.
 */
@Composable
private fun TabHeader(
    tab: LibraryTab,
    onSort: (() -> Unit)?,
    actions: ShellActions,
    /** Makes the title a menu — list or grid, on the album lists. */
    onTitleClick: (() -> Unit)? = null,
) {
    // More at the right where it reads as this page's own menu.
    // Sort is on the title. Bottom bar provides now-playing access.
    AppHeader(
        title = tabTitle(tab, likedOnly(tab)),
        onTitleClick = onSort ?: onTitleClick,
        rightAction = HeaderAction(AppIcons.MoreHoriz, actions.more),
    )
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
        TabHeader(
            LibraryTab.ALBUMS,
            actions.albumsSort,
            actions,
            // Only where there is a choice to make: with covers off there is no
            // grid to switch to.
            onTitleClick = actions.albumView.takeIf { !App.hideArtwork.collectAsState().value },
        )
        if (grid) {
            // No strip and no bar at either width: the covers take the whole
            // screen, which is the reason to be in a grid at all.
            Box(modifier = Modifier.weight(1f)) {
                AlbumGrid(
                    albums = sorted,
                    onOpen = { actions.openAlbum(it.id, Parent.Here) },
                    onLongPress = { actions.albumOptions(it.id) },
                    // Its own anchor, separate from the list's: see
                    // rememberGridAnchor.
                    state = rememberGridAnchor("tab:albums/grid", headerCount = 1),
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
            TabHeader(LibraryTab.SONGS, actions.songsSort, actions)
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
    LaunchedEffect(Unit) { App.library.primeArtistImages() }
    val needsAccess = !rememberLocalAccess()
    val audioPermission = rememberPermissionRequestLauncher(READ_MEDIA_AUDIO)
    Column(Modifier.fillMaxSize()) {
        TabHeader(LibraryTab.ARTISTS, actions.artistsSort, actions)
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
        TabHeader(LibraryTab.PLAYLISTS, actions.playlistsSort, actions)
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

@Composable
fun Navbar(
    current: LibraryTab?,
    onSearch: () -> Unit,
    onNowPlaying: () -> Unit,
    onBrowse: (() -> Unit)? = null,
    showChevron: Boolean = false,
    moreActive: Boolean = false,
) {
    // 3-item tool bar on library root and all subs: left=search, center=current
    // LibraryTab (with chevron only on root), right=now playing. Left and right
    // icons sit at the edges; center takes any remaining space.
     Row(
         modifier = Modifier
             .fillMaxWidth()
             .height(px(BOTTOM_BAR_PX))
             .padding(horizontal = 0.dp),
         horizontalArrangement = Arrangement.SpaceBetween,
         verticalAlignment = Alignment.CenterVertically,
     ) {
         NavIcon(AppIcons.Search) { onSearch() }
         Spacer(Modifier.weight(1f))
         BrowseCenter(current, showChevron) { onBrowse?.invoke() }
         Spacer(Modifier.weight(1f))
         NavIcon(AppIcons.Waveform) { onNowPlaying() }
     }
}

@Composable
private fun BrowseCenter(
    tab: LibraryTab?,
    showChevron: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(px(NAV_TILE_PX))
            .clip(RoundedCornerShape(px(NAV_TILE_RADIUS_PX)))
            .appClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val icon = tab?.let { iconFor(it) } ?: AppIcons.AlbumStack
        val scale = if (showChevron && tab != null) BROWSE_ICON_SCALE_WITH_CHEVRON else 1f
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icon = icon, size = n(28) * scale)
            if (showChevron && tab != null) {
                Spacer(Modifier.width(n(4)))
                AppIcon(AppIcons.ArrowDropDown, size = n(BROWSE_CHEVRON_SIZE))
            }
        }
    }
}

@Composable
private fun NavIcon(
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(px(NAV_TILE_PX))
            .clip(RoundedCornerShape(px(NAV_TILE_RADIUS_PX)))
            .appClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            icon = icon,
            size = n(28),
            tint = LightThemeTokens.colors.content,
        )
    }
}

private const val NAV_TILE_PX = 144
private const val NAV_TILE_RADIUS_PX = 6

/** Chevron shown next to type icon only on the library root. Match top-bar chevron (n(22)). */
private const val BROWSE_CHEVRON_SIZE = 22
/** Slight shrink so icon + chevron balance inside the NAV_TILE hit area. */
private const val BROWSE_ICON_SCALE_WITH_CHEVRON = 0.92f







@Composable
private fun listPadding(end: androidx.compose.ui.unit.Dp) =
    com.sublunar.amp.ui.components.listPadding(end = end)
