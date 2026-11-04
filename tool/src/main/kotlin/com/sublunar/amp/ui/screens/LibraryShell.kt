package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.ui.graphics.vector.ImageVector
import com.sublunar.amp.App
import com.sublunar.amp.data.Album
import com.sublunar.amp.data.LayoutMode
import com.sublunar.amp.data.SyncState
import com.sublunar.amp.data.TagFilter
import com.sublunar.amp.data.shuffled
import com.sublunar.amp.source.PhoneAccess
import com.sublunar.amp.source.PhoneLibrary
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.PlayerTheme
import com.sublunar.amp.ui.components.AlbumGrid
import com.sublunar.amp.ui.components.AlphabetIndex
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.ArtistRow
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.INDEX_STRIP_PX
import com.sublunar.amp.ui.components.LatestList
import com.sublunar.amp.ui.components.ListScrollBar
import com.sublunar.amp.ui.components.MENU_ROW_INSET_PX
import com.sublunar.amp.ui.components.PlayAllRow
import com.sublunar.amp.ui.components.SCROLLBAR_LANE_PX
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.SelectionHeader
import com.sublunar.amp.ui.components.ShuffleRow
import com.sublunar.amp.ui.components.TabGlyphs
import com.sublunar.amp.ui.components.TextRole
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.components.TitleCard
import com.sublunar.amp.ui.components.TrackRow
import com.sublunar.amp.ui.components.appClickable
import com.sublunar.amp.ui.components.listPadding
import com.sublunar.amp.ui.components.rememberGridAnchor
import com.sublunar.amp.ui.components.rememberLatest
import com.sublunar.amp.ui.components.rememberListAnchor
import com.sublunar.amp.ui.components.rememberScrollTarget
import com.sublunar.amp.ui.components.rememberSelection
import com.sublunar.amp.ui.components.rowClickable
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
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
    LibraryTab.ARTISTS -> TabGlyphs.Microphone
    LibraryTab.SONGS -> AppIcons.MusicNote
    LibraryTab.ALBUMS -> AppIcons.AlbumStack
}

/**
 * The library: a tab list, search or Simplified's index, over the bottom bar.
 * Drawn by the root screen, so everything it opens is pushed from there.
 */
@Composable
fun SimpleLightScreen<*>.LibraryShell() {
    val currentTab by LibraryNav.currentTab.collectAsState()
    val libraryIndex by LibraryNav.libraryIndex.collectAsState()
    val searchActive by LibraryNav.searchActive.collectAsState()
    val typing by LibraryNav.typing.collectAsState()
    val simplified = App.settings.layoutMode.state.collectAsState().value == LayoutMode.SIMPLIFIED
    PlayerTheme(library = true) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                if (searchActive) {
                    SearchView()
                } else if (libraryIndex) {
                    LibraryIndex()
                } else {
                    TabContent(currentTab)
                }
            }
            // Not while typing, where the keyboard takes the bottom. Over the
            // results it is back, with no tab lit: search is none of them, and
            // a tab tapped there leaves it for that tab. Simplified's results
            // are stock Music's page, which has no bar: its back is the way out.
            if (!searchActive || (!typing && !simplified)) {
                Navbar(
                    current = if (libraryIndex || searchActive) null else currentTab,
                    onSelect = { LibraryNav.tapTab(it) },
                    onSearch = { startSearch() },
                    onNowPlaying = { go { NowPlayingScreen(it) } },
                    onBrowse = { LibraryNav.pressLibrary() },
                    showLibrary = !libraryIndex || searchActive,
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
internal fun SimpleLightScreen<*>.TabContent(tab: LibraryTab, header: Boolean = true) {
    when (tab) {
        LibraryTab.ALBUMS -> AlbumsTab(header)
        LibraryTab.SONGS -> SongsTab(header)
        LibraryTab.ARTISTS -> ArtistsTab(header)
        LibraryTab.PLAYLISTS -> PlaylistsTab(header)
    }
}

/**
 * The header every tab shares: the page's name, More at the right corner (held,
 * Settings), and search beside it. Under Simplified, where the bar holds search,
 * the left corner is the way back to the library index instead.
 */
@Composable
private fun SimpleLightScreen<*>.TabHeader(tab: LibraryTab) {
    val simplified = App.settings.layoutMode.state.collectAsState().value == LayoutMode.SIMPLIFIED
    val title = tabTitle(tab, likedOnly(tab))
    // A filter shows as a second line, the title's own staying as it was.
    val filters = tabFilterLine(tab)
    AppHeader(
        title = if (filters == null) title else null,
        titleContent = filters?.let { line -> { TitleCard(title, line) } },
        onBack = if (simplified) ({ LibraryNav.openLibraryIndex() }) else null,
        // Beside the page's menu, as a way of narrowing the list like the sort
        // and filters. Straight to the keyboard: a magnifier is a request to type.
        innerAction = if (simplified) {
            null
        } else {
            HeaderAction(AppIcons.Search) { LibraryNav.openSearch(withKeyboard = true) }
        },
        fitTitle = true,
        rightAction = libraryCornerAction(tab.page),
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
 * The genre a tab has been narrowed to — see [TagFilterScreen].
 *
 * Only the two lists of things a genre describes have one: an artist is not a
 * genre's, and a playlist is whatever was put in it.
 */
@Composable
fun tagFilter(tab: LibraryTab): TagFilter = when (tab) {
    LibraryTab.ALBUMS -> App.albumsTagFilter.collectAsState().value
    LibraryTab.SONGS -> App.songsTagFilter.collectAsState().value
    else -> TagFilter()
}

/**
 * Whether this tab is currently showing only liked items; false for no tab. All
 * three are read whatever the tab, so a caller whose tab changes keeps following
 * — see ARCHITECTURE.md on conditional composable calls.
 */
@Composable
fun likedOnly(tab: LibraryTab?): Boolean {
    val albums = App.settings.likedAlbumsOnly.state.collectAsState().value
    val songs = App.settings.likedSongsOnly.state.collectAsState().value
    val artists = App.settings.likedArtistsOnly.state.collectAsState().value
    return when (tab) {
        LibraryTab.ALBUMS -> albums
        LibraryTab.SONGS -> songs
        LibraryTab.ARTISTS -> artists
        // Nothing likes a playlist.
        LibraryTab.PLAYLISTS, null -> false
    }
}

/** What the tab is called: "Liked" in front when only liked items show. The other filters get [tabFilterLine]. */
fun tabTitle(tab: LibraryTab, likedOnly: Boolean): String =
    if (likedOnly) "Liked " + tab.title else tab.title

/**
 * The filters narrowing a tab other than Liked, which the title already names —
 * Downloaded, then the genre — for the line under it; null with
 * none. On a line of its own, as the title has no room for them.
 */
@Composable
fun tabFilterLine(tab: LibraryTab): String? {
    val downloaded = downloadedOnly(tab)
    val tags = tagFilter(tab)
    val parts = listOfNotNull("Downloaded".takeIf { downloaded }, tags.genre.ifEmpty { null })
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
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
 * The rows a tab list shows above its content when something needs saying:
 *
 * - Allow Music Access, for a phone library not let in yet. Without it the tab
 *   is simply empty, which reads as "this phone has no music".
 * - Why the last sync gave up. A refused login leaves the cached rows in place,
 *   so this may sit above a library that looks fine and is no longer being
 *   updated. Tapping goes to Settings, the way to the source that needs fixing.
 * - What the sync is doing, while the list it is filling is still [empty]: a
 *   first sync of a large library takes minutes.
 *
 * A list counts them among its header rows, so an A–Z jump and a saved place
 * land on the right row.
 */
@Composable
private fun SimpleLightScreen<*>.libraryNotices(empty: Boolean): List<@Composable () -> Unit> {
    val needsAccess = !rememberLocalAccess()
    val askAccess = rememberAccessRequest(App.source.collectAsState().value.phoneLibrary)
    val sync = App.librarySync.syncState.collectAsState().value
    val error = sync.error
    return buildList<@Composable () -> Unit> {
        if (needsAccess) add { PlayAllRow(AppIcons.Smartphone, "Allow Music Access", onClick = askAccess) }
        if (!error.isNullOrBlank()) add { PlayAllRow(AppIcons.CloudOff, error) { go { SettingsScreen(it) } } }
        if (empty && sync.syncing) add { SectionLabel(syncProgressText(sync.phase)) }
    }
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
private fun SimpleLightScreen<*>.AlbumsTab(header: Boolean = true) {
    val view by App.sortedAlbums.collectAsState()
    val sorted = view.items
    val rows = rememberLatest(sorted)
    val letters = view.letters
    val reversed by App.settings.albumSortReversed.state.collectAsState()
    val downloadedAlbums by App.library.downloadedAlbumIds.collectAsState()
    val notices = libraryNotices(sorted.isEmpty())
    val grid = App.albumGrid.collectAsState().value
    Column(Modifier.fillMaxSize()) {
        if (header) TabHeader(LibraryTab.ALBUMS)
        if (grid) {
            // Its own anchor, separate from the list's: see rememberGridAnchor.
            val gridState = rememberGridAnchor("tab:albums/grid", headerCount = 1 + notices.size)
            // No strip: the covers take the width, and AlbumGrid adds its own bar
            // once the list is long.
            Box(modifier = Modifier.weight(1f)) {
                AlbumGrid(
                    albums = sorted,
                    onOpen = { openAlbum(it.id, Parent.Here) },
                    onLongPress = { album -> go { AlbumActionsScreen(it, album.id) } },
                    state = gridState,
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        RandomAlbumRow(rows)
                    }
                    notices.forEach { notice -> item(span = { GridItemSpan(maxLineSpan) }) { notice() } }
                }
            }
        } else {
            IndexedList(
                anchor = "tab:albums",
                // The index only makes sense while the list is in name order.
                letters = letters,
                // The action row and the notices, so the A–Z strip lands on
                // the right album. Counted with or without the page's header:
                // search borrows the list, rows and all.
                headerCount = 1 + notices.size,
                reversed = reversed,
            ) {
                item { RandomAlbumRow(rows) }
                notices.forEach { notice -> item { notice() } }
                items(sorted.size, key = { rows[it]?.id ?: it }) { i ->
                    val album = rows[i] ?: return@items
                    TrackRow(
                        title = album.title,
                        subtitle = album.artist,
                        coverArtId = album.coverArtId,
                        fallback = AppIcons.Album,
                        downloaded = album.id in downloadedAlbums,
                        onClick = { openAlbum(album.id, Parent.Here) },
                        onLongClick = { go { AlbumActionsScreen(it, album.id) } },
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
private fun SimpleLightScreen<*>.RandomAlbumRow(albums: LatestList<Album>) {
    ShuffleRow("Play Random Album") {
        val album = albums.items.randomOrNull() ?: return@ShuffleRow
        App.scope.launch {
            // The database read off Main, the player call on it: the player's
            // looper is Main — see PlaybackController.
            val queue = App.library.albumQueue(album.id)
            if (queue.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                App.playback.playQueue(queue, 0)
                go { NowPlayingScreen(it) }
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
                headerCount = headerCount,
                reversed = reversed,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun SimpleLightScreen<*>.SongsTab(header: Boolean = true) {
    val view by App.sortedSongs.collectAsState()
    val sorted = view.items
    val rows = rememberLatest(sorted)
    val letters = view.letters
    val reversed by App.settings.songSortReversed.state.collectAsState()
    val downloadedIds by App.library.downloadedTrackIds.collectAsState()
    val selection = rememberSelection("songs")
    val notices = libraryNotices(sorted.isEmpty())

    Column(Modifier.fillMaxSize()) {
        if (header) {
            if (selection.active) {
                SelectionHeader(selection) {
                    openSelectionActions(selection.pick(rows.items) { it.id }, selection)
                }
            } else {
                TabHeader(LibraryTab.SONGS)
            }
        }
        IndexedList(
            anchor = "tab:songs",
            letters = letters,
            headerCount = if (selection.active) 0 else 1 + notices.size,
            reversed = reversed,
        ) {
            if (!selection.active) {
                item {
                    ShuffleRow {
                        App.playback.playQueue(shuffled(rows.items), 0)
                        go { NowPlayingScreen(it) }
                    }
                }
                notices.forEach { notice -> item { notice() } }
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
                            go { NowPlayingScreen(it) }
                        }
                    },
                    onLongClick = {
                        if (!selection.active) openTrackActions(track.id, selection)
                    },
                )
            }
        }
    }
}

@Composable
private fun SimpleLightScreen<*>.ArtistsTab(header: Boolean = true) {
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
    val notices = libraryNotices(sorted.isEmpty())
    Column(Modifier.fillMaxSize()) {
        if (header) TabHeader(LibraryTab.ARTISTS)
        IndexedList(
            anchor = "tab:artists",
            letters = letters,
            headerCount = notices.size,
            reversed = reversed,
        ) {
            notices.forEach { notice -> item { notice() } }
            items(sorted.size, key = { rows[it]?.name ?: it }) { i ->
                val artist = rows[i] ?: return@items
                ArtistRow(
                    name = artist.name,
                    downloaded = artist.name in downloadedArtists,
                    imageId = artist.imageId,
                    onClick = { openArtist(artist.name, Parent.Here) },
                    onLongClick = { go { ArtistActionsScreen(it, artist.name) } },
                )
            }
        }
    }
}

@Composable
private fun SimpleLightScreen<*>.PlaylistsTab(header: Boolean = true) {
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
        if (header) TabHeader(LibraryTab.PLAYLISTS)
        IndexedList(
            anchor = "tab:playlists",
            letters = letters,
            headerCount = if (canCreateEmpty) 1 else 0,
            reversed = reversed,
        ) {
            // A server that can't hold an empty playlist has no use for a bare
            // "New Playlist" — see MusicSource.supportsEmptyPlaylists.
            if (canCreateEmpty) {
                item { PlayAllRow(AppIcons.Add, "New Playlist", headsList = true, oneLineList = true) { newPlaylist() } }
            }
            items(playlists, key = { it.id }) { playlist ->
                TrackRow(
                    title = playlist.name,
                    subtitle = "",
                    coverArtId = playlist.coverArtId,
                    fallback = AppIcons.QueueMusic,
                    downloaded = playlist.id in downloadedPlaylists,
                    onClick = { go { PlaylistDetailScreen(it, playlist.id, playlist.name) } },
                    onLongClick = { go { PlaylistActionsScreen(it, playlist.id, playlist.name) } },
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
private fun SimpleLightScreen<*>.LibraryIndex() {
    val source by App.source.collectAsState()
    // The liked lists appear only where there is something in them, and not at
    // all where the source has no likes — see MusicSource.supportsLikes.
    val likedArtists by App.library.hasLikedArtists.collectAsState()
    val likedAlbums by App.library.hasLikedAlbums.collectAsState()
    val likedTracks by App.library.hasLikedTracks.collectAsState()
    val likes = source.supportsLikes
    val likedAsFilter = App.settings.likedAsFilter.state.collectAsState().value

    // A Column of its own: the shell hands each page a Box.
    Column(modifier = Modifier.fillMaxSize()) {
        AppHeader(
            // The source's name with the SDK's chevron: a picker, as the library's
            // name is at the top of Plexamp. Choosing a source is the whole
            // library's business, so this page is where it lives in this layout.
            titleContent = { SourceTitle(source.name) { go { SourcesScreen(it) } } },
            // Settings' one place in this layout, two taps from anywhere: the
            // More menus leave it out here.
            rightAction = HeaderAction(AppIcons.Settings) { go { SettingsScreen(it) } },
        )
        ScrollableList {
            item { IndexRow(LibraryTab.PLAYLISTS) }
            item { IndexRow(LibraryTab.ARTISTS) }
            item { IndexRow(LibraryTab.ALBUMS) }
            item { IndexRow(LibraryTab.SONGS) }
            // The same three again, narrowed, as a block under the plain lists —
            // or, with Liked as Filter (TRIAL), a switch on each tab's More page.
            if (likes && likedArtists && !likedAsFilter) {
                item { LikedRow(LibraryTab.ARTISTS) }
            }
            if (likes && likedAlbums && !likedAsFilter) {
                item { LikedRow(LibraryTab.ALBUMS) }
            }
            if (likes && likedTracks && !likedAsFilter) {
                item { LikedRow(LibraryTab.SONGS) }
            }
        }
    }
}

@Composable
private fun SourceTitle(name: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().appClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppText(
                name,
                pxSp(LightType.FINE_PX),
                role = TextRole.Subheading,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(px(SOURCE_CHEVRON_GAP_PX)))
            LightIcon(LightIcons.DOWN, size = SOURCE_CHEVRON_UNITS, contentDescription = "Choose source")
        }
    }
}

/** The chevron at one grid unit, about the title's cap height, a little apart from it. */
private const val SOURCE_CHEVRON_UNITS = 1f
private const val SOURCE_CHEVRON_GAP_PX = 16

/** One tab, on the index: its own bar icon, and its name. */
@Composable
private fun IndexRow(tab: LibraryTab) {
    val likedAsFilter = App.settings.likedAsFilter.state.collectAsState().value
    IndexLine(tab.title, iconFor(tab)) {
        // The unnarrowed list: "Albums" showing only the liked ones would lie.
        // With Liked as a switch (TRIAL), it is the switch's to say, as on the tabs.
        if (!likedAsFilter) App.scope.launch { setLikedOnly(tab, false) }
        LibraryNav.selectTab(tab)
    }
}

/** The same tab, narrowed to what you kept. */
@Composable
private fun LikedRow(tab: LibraryTab) {
    IndexLine("Liked ${tab.title}", AppIcons.Favorite) {
        App.scope.launch { setLikedOnly(tab, true) }
        LibraryNav.selectTab(tab)
    }
}

/**
 * A row of the index: a menu of places, so the settings pages' Heading type, on
 * a pitch that fits six whole rows in the 920px between the header and the bar;
 * a seventh (all three liked lists) scrolls. The glyph on the menus' text axis,
 * the name after it.
 */
@Composable
private fun IndexLine(title: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(INDEX_ROW_PX))
            .rowClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(px(MENU_ROW_INSET_PX)))
        AppIcon(icon, size = px(INDEX_GLYPH_PX))
        Spacer(Modifier.width(px(INDEX_GLYPH_GAP_PX)))
        AppText(
            title,
            pxSp(LightType.HEADING_PX),
            lineHeight = pxSp(LightType.HEADING_LINE_PX),
            role = TextRole.Heading,
            maxLines = 1,
        )
    }
}

private const val INDEX_ROW_PX = 153
private const val INDEX_GLYPH_PX = 66
private const val INDEX_GLYPH_GAP_PX = 40

/** Whether this tab is narrowed to what is downloaded — see AppSettings.downloadedAlbumsOnly. */
@Composable
fun downloadedOnly(tab: LibraryTab?): Boolean {
    val albums = App.settings.downloadedAlbumsOnly.state.collectAsState().value
    val songs = App.settings.downloadedSongsOnly.state.collectAsState().value
    val artists = App.settings.downloadedArtistsOnly.state.collectAsState().value
    return when (tab) {
        LibraryTab.ALBUMS -> albums
        LibraryTab.SONGS -> songs
        LibraryTab.ARTISTS -> artists
        else -> false
    }
}

suspend fun setDownloadedOnly(tab: LibraryTab, value: Boolean) {
    when (tab) {
        LibraryTab.ALBUMS -> App.settings.downloadedAlbumsOnly.set(value)
        LibraryTab.SONGS -> App.settings.downloadedSongsOnly.set(value)
        LibraryTab.ARTISTS -> App.settings.downloadedArtistsOnly.set(value)
        LibraryTab.PLAYLISTS -> Unit
    }
}

suspend fun setLikedOnly(tab: LibraryTab, value: Boolean) {
    when (tab) {
        LibraryTab.ALBUMS -> App.settings.likedAlbumsOnly.set(value)
        LibraryTab.SONGS -> App.settings.likedSongsOnly.set(value)
        LibraryTab.ARTISTS -> App.settings.likedArtistsOnly.set(value)
        LibraryTab.PLAYLISTS -> Unit
    }
}
