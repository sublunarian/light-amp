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
import androidx.compose.runtime.DisposableEffect
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
import com.sublunar.amp.App
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import kotlinx.coroutines.launch
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

class ShellActions(
    val nowPlaying: () -> Unit,
    val settings: () -> Unit,
    val search: () -> Unit,
    /** Opens the full-screen LP3 keyboard to edit the search query. */
    val editSearch: (String) -> Unit,
    val more: () -> Unit,
    val openAlbum: (String) -> Unit,
    val openArtist: (String) -> Unit,
    val openPlaylist: (String, String) -> Unit,
    val likedAlbums: () -> Unit,
    val likedSongs: () -> Unit,
    val likedArtists: () -> Unit,
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
    onSelectTab: (LibraryTab) -> Unit,
    searchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onSearchClear: () -> Unit,
    actions: ShellActions,
) {
    // Composed only while this is the top of the back stack, which is exactly
    // when a tab's own list is what the user can see — see LibraryNav.shellShowing.
    DisposableEffect(Unit) {
        LibraryNav.shellShowing.value = true
        onDispose { LibraryNav.shellShowing.value = false }
    }
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
                onSelect = onSelectTab,
                onMore = actions.more,
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
                    TextRow(title = artist.name) { onClose(); actions.openArtist(artist.name) }
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
                        onClick = { onClose(); actions.openAlbum(album.id) },
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
 * The header every tab shares: sort at the left corner, search and now-playing at
 * the right, and — when the setting puts it there — the liked/all switch in the
 * mirror of the search slot.
 */
@Composable
private fun TabHeader(
    title: String,
    onSort: (() -> Unit)?,
    actions: ShellActions,
    /** The liked list, when the setting puts its switch up here. */
    onLiked: (() -> Unit)? = null,
    /** Makes the title a menu — list or grid, on the album lists. */
    onTitleClick: (() -> Unit)? = null,
) {
    AppHeader(
        title = title,
        leftAction = onSort?.let { HeaderAction(AppIcons.Sort, it) },
        // Outlined: this is the way *to* the liked list. The liked pages show it
        // filled, which is also how they get back.
        secondaryLeftAction = onLiked?.let { HeaderAction(AppIcons.FavoriteBorder, it) },
        onTitleClick = onTitleClick,
        searchAction = actions.search,
        rightAction = HeaderAction(AppIcons.Waveform, actions.nowPlaying),
    )
}

/**
 * Whether the phone's own music folder can be read, re-checked whenever a sync
 * finishes — granting access is something the user leaves the app to do, and
 * coming back is when the answer changes.
 *
 * False only ever means "not allowed yet": an empty folder still lists.
 */
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
    val likedAlbums by App.library.likedAlbums.collectAsState()
    val hasLiked = supportsLikes && likedAlbums.isNotEmpty()
    val needsAccess = !rememberLocalAccess()
    val audioPermission = rememberPermissionRequestLauncher(READ_MEDIA_AUDIO)
    val grid = App.albumGrid.collectAsState().value
    Column(Modifier.fillMaxSize()) {
        TabHeader(
            "All Albums",
            actions.albumsSort,
            actions,
            onLiked = actions.likedAlbums.takeIf { hasLiked },
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
                    onOpen = { actions.openAlbum(it.id) },
                    onLongPress = { actions.albumOptions(it.id) },
                    // Its own anchor, separate from the list's: see
                    // rememberGridAnchor.
                    state = rememberGridAnchor("tab:albums/grid"),
                )
            }
        } else {
            IndexedList(
                anchor = "tab:albums",
                // The index only makes sense while the list is in name order.
                letters = letters,
                headerCount = 0,
                reversed = reversed,
            ) {
                localAccessNotice(needsAccess) { audioPermission?.launch() }
                // Nothing above the shelf: the heart lives in the header, and
                // Play or Shuffle over a whole library is a rarer thing to want
                // than it looks.
                items(sorted, key = { it.id }) { album ->
                    TrackRow(
                        title = album.title,
                        subtitle = album.artist,
                        coverArtId = album.coverArtId,
                        fallback = AppIcons.Album,
                        downloaded = album.id in downloadedAlbums,
                        onClick = { actions.openAlbum(album.id) },
                        onLongClick = { actions.albumOptions(album.id) },
                    )
                }
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
    val likedTracks by App.library.likedTracks.collectAsState()
    val hasLiked = supportsLikes && likedTracks.isNotEmpty()
    val needsAccess = !rememberLocalAccess()
    val audioPermission = rememberPermissionRequestLauncher(READ_MEDIA_AUDIO)

    Column(Modifier.fillMaxSize()) {
        if (selection.active) {
            SelectionHeader(selection) {
                actions.selectionActions(selection.pick(sorted) { it.id }, selection)
            }
        } else {
            TabHeader(
                "All Songs",
                actions.songsSort,
                actions,
                onLiked = actions.likedSongs.takeIf { hasLiked },
            )
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
                    SplitActionRow(
                        leftIcon = AppIcons.PlayArrow,
                        leftLabel = "Play",
                        onLeft = {
                            App.playback.playQueue(sorted, 0)
                            actions.nowPlaying()
                        },
                        rightIcon = AppIcons.Shuffle,
                        rightLabel = "Shuffle",
                        onRight = {
                            App.playback.playQueue(shuffled(sorted), 0)
                            actions.nowPlaying()
                        },
                    )
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
    val likedArtists by App.library.likedArtists.collectAsState()
    val hasLiked = supportsLikes && likedArtists.isNotEmpty()
    val downloadedArtists by App.library.downloadedArtistNames.collectAsState()
    val needsAccess = !rememberLocalAccess()
    val audioPermission = rememberPermissionRequestLauncher(READ_MEDIA_AUDIO)
    Column(Modifier.fillMaxSize()) {
        TabHeader(
            "All Artists",
            actions.artistsSort,
            actions,
            onLiked = actions.likedArtists.takeIf { hasLiked },
        )
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
                    onClick = { actions.openArtist(artist.name) },
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
        TabHeader("Playlists", actions.playlistsSort, actions)
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
    onSelect: (LibraryTab) -> Unit,
    onMore: () -> Unit,
    moreActive: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Matches the LP3's stock bottom bar: 4 grid units = 160px exactly,
            // no top margin. No vertical padding, so icons centre on 80px.
            .height(px(BOTTOM_BAR_PX))
            .padding(horizontal = px(NAV_EDGE_PX)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A source with no server has nowhere to keep a playlist, so the tab
        // isn't there to be tapped — see MusicSource.supportsPlaylists.
        if (App.source.collectAsState().value.supportsPlaylists) {
            // Near enough the glyph's own size to still be the reference the
            // others are brought down towards, but trimmed and lifted slightly:
            // it sat a shade large and a shade low against its neighbours.
            NavIcon(
                AppIcons.QueueMusic,
                current == LibraryTab.PLAYLISTS,
                scale = PLAYLISTS_SCALE,
                lift = PLAYLISTS_LIFT_PX,
            ) { onSelect(LibraryTab.PLAYLISTS) }
        }
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
        // Not normalised: three dots in a row are 18px tall whatever box they
        // are given, and scaling them to a common *height* would blow them up to
        // the width of the bar. Its width already matches its neighbours.
        NavIcon(AppIcons.MoreHoriz, moreActive, onClick = onMore)
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

/**
 * The tile a selected tab inverts into, and the glyph inside it.
 *
 * The glyph is close to the size it was before tabs gained a tile — shrinking it
 * to fit inside one made the whole bar read as smaller than the app around it.
 * The tile stays *under* [BOTTOM_BAR_PX]: at 162 it was taller than the bar
 * itself, which is what made the bar look as though it had grown.
 */
private const val NAV_TILE_PX = 144
private const val NAV_TILE_RADIUS_PX = 6
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

/** Where the tall glyphs land: above the playlist's own 66, well under their 90. */
private const val TALL_TARGET_PX = 72

/** A mic is the narrowest of them, so it carries a little more height than the rest. */
private const val ARTISTS_TARGET_PX = 75

private fun navScale(drawnPx: Int, targetPx: Int): Float = targetPx.toFloat() / drawnPx

/** A shade off the reference size, and a shade higher in the tile. Judged by eye. */
private const val PLAYLISTS_SCALE = 0.94f
private const val PLAYLISTS_LIFT_PX = 5

private const val NAV_EDGE_PX = 30



@Composable
private fun listPadding(end: androidx.compose.ui.unit.Dp) =
    com.sublunar.amp.ui.components.listPadding(end = end)
