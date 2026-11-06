package com.sublunar.amp.ui.screens

import com.sublunar.amp.data.ArtistCredit
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sublunar.amp.App
import com.sublunar.amp.data.Album
import com.sublunar.amp.data.ArtistAlbumOrder
import com.sublunar.amp.data.LayoutMode
import com.sublunar.amp.data.ReleaseType
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.nameKey
import com.sublunar.amp.data.shuffled
import com.sublunar.amp.data.titleKey
import com.sublunar.amp.ui.components.ActionList
import com.sublunar.amp.ui.components.AlbumGrid
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.EmptyState
import com.sublunar.amp.ui.components.ROW_LEAD_PX
import com.sublunar.amp.ui.components.rememberNumberColumn
import com.sublunar.amp.ui.components.formatTime
import androidx.compose.foundation.layout.Column
import com.sublunar.amp.ui.components.rowClickable
import com.sublunar.amp.ui.components.ROW_H_PX
import com.sublunar.amp.ui.pxSp
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.ROW_TYPE
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import com.sublunar.amp.ui.px
import androidx.compose.foundation.layout.padding
import com.sublunar.amp.ui.components.LibraryList
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.SelectionHeader
import com.sublunar.amp.ui.components.ShuffleRow
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.components.TitleCard
import com.sublunar.amp.ui.components.TrackRow
import com.sublunar.amp.ui.components.rememberGridAnchor
import com.sublunar.amp.ui.components.rememberSelection
import com.thelightphone.sdk.SealedLightActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** An artist's page: their discography, with the ways into their songs on top. */
class ArtistDetailScreen(
    sealed: SealedLightActivity,
    private val name: String,
    /** SONG from the Artists list's Artist order: the records their songs are on. */
    private val credit: ArtistCredit = ArtistCredit.ALBUM,
) : AmpScreen<Unit>(sealed) {

    /**
     * After the records: Popular Tracks as a heading that opens the whole list,
     * its first [POPULAR_PREVIEW] songs to play from, each numbered by its rank
     * before its cover, its length under the title; then, with [allSongs], All
     * Tracks, a heading of the same kind. An artist of one record leaves it out:
     * that record already holds their songs.
     */
    @Composable
    private fun SongsAtEnd(popular: List<Track>, allSongs: Boolean, downloadedIds: Set<String>) {
        val shown = popular.take(POPULAR_PREVIEW)
        val numberColumn = rememberNumberColumn(remember(shown.size) { List(shown.size) { it + 1 } })
        if (popular.isNotEmpty()) {
            HeadingRow("Popular Tracks", ROW_H_PX) {
                go { ArtistSongsScreen(it, name, popular = true, credit = credit) }
            }
            shown.forEachIndexed { index, track ->
                TrackRow(
                    title = track.title,
                    subtitle = formatTime(track.durationMs),
                    coverArtId = track.coverArtId,
                    downloaded = track.id in downloadedIds,
                    onClick = { playFromRow(popular, index) },
                    onLongClick = { openTrackActions(track.id, null, onArtistPage = name) },
                    number = index + 1,
                    numberColumn = numberColumn,
                )
            }
        }
        if (allSongs) HeadingRow("All Tracks", ROW_H_PX) { go { ArtistSongsScreen(it, name, credit = credit) } }
    }

    @Composable
    override fun Content() {
        val tracks by App.library.tracks.collectAsState()
        val albums by App.library.albums.collectAsState()
        val allArtistAlbums = remember(tracks, albums, name) { App.library.albumsForArtist(name, credit) }
        val downloadedAlbums by App.library.downloadedAlbumIds.collectAsState()
        // Reached from Downloads: the records with a song on the phone, and
        // Popular only if one of its songs is.
        val downloadsOnly by LibraryNav.downloadsOnly.collectAsState()
        val withDownloads by App.albumsWithDownloads.collectAsState()
        val downloadedIds by App.library.downloadedTrackIds.collectAsState()
        val artistAlbums = remember(allArtistAlbums, downloadsOnly, withDownloads) {
            if (downloadsOnly) allArtistAlbums.filter { it.id in withDownloads } else allArtistAlbums
        }

        // Popular only when the server returns some (Navidrome needs Last.fm set up).
        var popular by remember(name) { mutableStateOf<List<Track>>(emptyList()) }
        LaunchedEffect(name, downloadsOnly, downloadedIds) {
            val top = App.library.topSongsForArtist(name)
            popular = if (downloadsOnly) top.filter { it.id in downloadedIds } else top
        }

        val grid = App.artistAlbumGrid.collectAsState().value
        val order by App.settings.artistAlbumOrder.state.collectAsState()
        // By name as the Albums tab sorts by title; otherwise the dates' order.
        val ordered = remember(artistAlbums, order) {
            if (order == ArtistAlbumOrder.NAME) artistAlbums.sortedBy { nameKey(it.title) } else artistAlbums
        }
        // Grouped only where the library knows release types (see
        // knowsReleaseTypes); otherwise the records keep their dates' order.
        // Singles and EPs as one, the dates' order in each group.
        // Every group headed, one alone included, so the page is the same shape
        // before and after the popular tracks come.
        val groups = remember(artistAlbums, albums, order) {
            if (order == ArtistAlbumOrder.RELEASE_TYPE && knowsReleaseTypes(albums)) recordGroups(artistAlbums) else null
        }
        val shown = remember(groups, ordered) { groups?.flatMap { it.albums } ?: ordered }
        // Without covers, records under a heading sit a step in from it.
        val artless = App.hideListArtwork.collectAsState().value
        val recordIndentPx = if (artless && groups != null) RECORD_INDENT_PX else 0

        LibrarySubPage {
            AppHeader(
                onBack = { goBack() },
                title = name,
                fitTitle = true,
                rightAction = libraryCornerAction(LibraryPage.ARTIST, name),
            )
            if (grid) {
                // Each group's heading, and its Play row, before its first sleeve.
                val headings = remember(groups) {
                    var at = 0
                    groups.orEmpty().associate { group ->
                        val start = at
                        at += group.albums.size
                        start to @Composable {
                            HeadingRow(group.heading.orEmpty(), ROW_H_PX) {
                                go { GroupPlayScreen(it, name, group.heading.orEmpty(), group.albums) }
                            }
                        }
                    }
                }
                AlbumGrid(
                    albums = shown,
                    headings = headings,
                    onOpen = { album -> openAlbum(album.id, Parent.Here) },
                    onLongPress = { album -> go { AlbumActionsScreen(it, album.id, onArtistPage = name) } },
                    // One anchor per artist. The ways into the songs come after the
                    // records, spanning the row.
                    state = rememberGridAnchor("artist-albums:$name/grid"),
                    footer = {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column { SongsAtEnd(popular, allSongs = artistAlbums.size > 1, downloadedIds) }
                        }
                    },
                ) {}
                return@LibrarySubPage
            }
            LibraryList(
                anchor = "artist-albums:$name",
                headerCount = 0,
            ) {
                // A discography, the ways into the songs after it: liking is on
                // the artist's long-press.
                val sections = groups ?: listOf(RecordGroup(null, ordered))
                sections.forEach { group ->
                    if (group.heading != null) {
                        item(key = "group-${group.heading}") {
                            HeadingRow(group.heading, ROW_H_PX) {
                                go { GroupPlayScreen(it, name, group.heading, group.albums) }
                            }
                        }
                    }
                    items(group.albums, key = { it.id }) { album ->
                        TrackRow(
                            title = album.title,
                            subtitle = album.year?.toString() ?: "",
                            coverArtId = album.coverArtId,
                            fallback = AppIcons.Album,
                            downloaded = album.id in downloadedAlbums,
                            onClick = { openAlbum(album.id, Parent.Here) },
                            onLongClick = { go { AlbumActionsScreen(it, album.id, onArtistPage = name) } },
                            tall = true,
                            extraLeadPx = recordIndentPx,
                        )
                    }
                }
                item(key = "songs-at-end") {
                    Column { SongsAtEnd(popular, allSongs = artistAlbums.size > 1, downloadedIds) }
                }
            }
        }
    }

}

/** Long-press sheet for an artist, and the one place an artist is liked. */
class ArtistActionsScreen(
    sealed: SealedLightActivity,
    private val name: String,
    private val credit: ArtistCredit = ArtistCredit.ALBUM,
) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val liked = name in App.library.likedArtistNames.collectAsState().value
        val source by App.source.collectAsState()
        val tracks by App.library.tracks.collectAsState()
        val artistTracks = remember(tracks, name) { App.library.tracksForArtist(name, credit) }
        val downloadedArtists by App.library.downloadedArtistNames(credit).collectAsState()
        val fullyDownloaded = artistTracks.isNotEmpty() && name in downloadedArtists

        ListScreen(onBack = { goBack() }, title = name, headingRows = true) {
            ActionList {
                if (source.supportsLikes) {
                    TextRow(title = if (liked) "Unlike Artist" else "Like Artist") {
                        App.scope.launch { App.library.setArtistLiked(name, !liked) }
                        goBack()
                    }
                }
                TextRow(title = "All Songs") { go { ArtistSongsScreen(it, name, credit = credit) } }
                DownloadRow(source, artistTracks, fullyDownloaded, "Download All Albums")
            }
        }
    }
}

/**
 * An artist's songs: every one in the library, or with [popular] the server's
 * top tracks in popularity order. Just those: shuffling Popular shouldn't drop
 * the whole discography into the queue.
 */
class ArtistSongsScreen(
    sealed: SealedLightActivity,
    private val name: String,
    private val popular: Boolean = false,
    private val credit: ArtistCredit = ArtistCredit.ALBUM,
) : AmpScreen<Unit>(sealed) {

    // Each visit to an artist's songs starts plain, and leaves nothing behind:
    // a filter carried to the next artist would narrow it with nothing on the
    // way in to say so. Popular has its own order and no filters.
    init {
        if (!popular) ArtistSongsVisit.view.value = ArtistSongsView()
    }

    override fun onScreenDestroy() {
        if (!popular) ArtistSongsVisit.view.value = ArtistSongsView()
    }

    @Composable
    override fun Content() {
        // Fixed for the page, so the branch never flips between compositions.
        val all = if (popular) rememberPopular() else rememberAll()
        val downloadedIds by App.library.downloadedTrackIds.collectAsState()
        val view by ArtistSongsVisit.view.collectAsState()
        val albums by App.library.albums.collectAsState()
        // Reached from Downloads: only what is on the phone, Popular included.
        val downloadsOnly by LibraryNav.downloadsOnly.collectAsState()
        val songs = remember(all, view, downloadedIds, albums, downloadsOnly) {
            val onPhone = if (downloadsOnly) all?.filter { it.id in downloadedIds } else all
            if (popular || onPhone == null) onPhone else view.applyTo(onPhone, downloadedIds, albums)
        }
        // Anchor and selection owner both: the two lists never share either.
        val owner = if (popular) "artist-top:$name" else "artist-songs:$name"
        val selection = rememberSelection(owner)

        LibrarySubPage {
            if (selection.active) {
                SelectionHeader(selection) {
                    openSelectionActions(selection.pick(songs.orEmpty()) { it.id }, selection)
                }
            } else {
                // The artist alone, as on its other pages: the Popular row that
                // led here already said which songs these are.
                // The narrowing on a second line, Liked included: the title is
                // the artist's name, with no room to say it.
                val filters = if (popular) null else view.filterLine()
                // Popular Songs' More holds only the source, which the three
                // buttons keep on the library page instead: no menu to open.
                val simplified = App.settings.layoutMode.state.collectAsState().value == LayoutMode.SIMPLIFIED
                AppHeader(
                    onBack = { goBack() },
                    title = if (filters == null) name else null,
                    titleContent = filters?.let { line -> { TitleCard(name, line) } },
                    rightAction = if (popular && simplified) {
                        null
                    } else {
                        libraryCornerAction(
                            if (popular) LibraryPage.ARTIST_POPULAR else LibraryPage.ARTIST_SONGS,
                            name,
                        )
                    },
                    fitTitle = true,
                )
            }
            when {
                songs == null -> EmptyState("Loading…")
                popular && songs.isEmpty() -> EmptyState("No popular songs")
                songs.isEmpty() && view.narrows -> EmptyState("No songs match")
                else -> LibraryList(
                    anchor = owner,
                    headerCount = if (selection.active) 0 else 1,
                    scrollBar = false,
                ) {
                    if (!selection.active) {
                        item {
                            ShuffleRow {
                                App.playback.playQueue(shuffled(songs), 0)
                                go { NowPlayingScreen(it) }
                            }
                        }
                    }
                    itemsIndexed(songs, key = { _, t -> t.id }) { index, track ->
                        TrackRow(
                            title = track.title,
                            subtitle = track.album,
                            coverArtId = track.coverArtId,
                            downloaded = track.id in downloadedIds,
                            selected = if (selection.active) track.id in selection.selected else null,
                            onClick = {
                                if (selection.active) {
                                    selection.toggle(track.id)
                                } else {
                                    playFromRow(songs, index)
                                }
                            },
                            onLongClick = {
                                if (!selection.active) openTrackActions(track.id, selection, onArtistPage = name)
                            },
                        )
                    }
                }
            }
        }
    }

    /** The server's top tracks, asked for once per visit: null until they come. */
    @Composable
    private fun rememberPopular(): List<Track>? {
        var songs by remember(name) { mutableStateOf<List<Track>?>(null) }
        LaunchedEffect(name) { songs = App.library.topSongsForArtist(name) }
        return songs
    }

    /** Every song of theirs in the library, following it as it syncs. */
    @Composable
    private fun rememberAll(): List<Track> {
        val tracks by App.library.tracks.collectAsState()
        return remember(tracks, name) { App.library.tracksForArtist(name, credit) }
    }
}

/** How an artist's All Songs page is looked at, for one visit — see ArtistSongsScreen. */
data class ArtistSongsView(
    val liked: Boolean = false,
    val downloaded: Boolean = false,
    val sort: ArtistSongSort = ArtistSongSort.ALBUM,
) {
    val narrows: Boolean get() = liked || downloaded

    /** [all] narrowed and ordered: by album is the order it comes in. */
    fun applyTo(all: List<Track>, downloadedIds: Set<String>, albums: List<Album>): List<Track> {
        val narrowed = all.filter { (!liked || it.liked) && (!downloaded || it.id in downloadedIds) }
        return when (sort) {
            ArtistSongSort.ALBUM -> narrowed
            ArtistSongSort.TITLE -> narrowed.sortedBy { titleKey(it.title) }
            ArtistSongSort.PLAYS -> narrowed.sortedByDescending { it.playCount }
            ArtistSongSort.ADDED -> {
                val added = albums.associate { it.id to it.createdMs }
                narrowed.sortedByDescending { added[it.albumId] ?: 0L }
            }
        }
    }

    /** The header's second line: what narrows the list, or null. */
    fun filterLine(): String? =
        listOfNotNull("Liked".takeIf { liked }, "Downloaded".takeIf { downloaded })
            .takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** The orders an artist's songs can be put in; by album is how they come. */
enum class ArtistSongSort(val label: String) {
    ALBUM("Album"),
    TITLE("Title"),
    PLAYS("Most Played"),
    ADDED("Recently Added"),
}

/** The visit in progress — its More page reads and changes it. */
object ArtistSongsVisit {
    val view = MutableStateFlow(ArtistSongsView())
}

/** "Sort by" for an artist's songs: picking one applies it and closes, as the tabs' do. */
class ArtistSongSortScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val view by ArtistSongsVisit.view.collectAsState()
        ListScreen(onBack = { goBack() }, title = "Sort by", subtitle = "Songs", headingRows = true) {
            ScrollableList {
                items(ArtistSongSort.entries.toList()) { sort ->
                    TextRow(title = sort.label, checked = sort == view.sort) {
                        ArtistSongsVisit.view.value = view.copy(sort = sort)
                        goBack(Unit)
                    }
                }
            }
        }
    }
}

/** One group of an artist's records under its heading, or with none for a page that isn't grouped. */
internal data class RecordGroup(val heading: String?, val albums: List<Album>)

/** [albums] by release type, Singles and EPs as one group, each keeping the order it came in. */
internal fun recordGroups(albums: List<Album>): List<RecordGroup> =
    RECORD_GROUPS.mapNotNull { (heading, types) ->
        albums.filter { it.releaseType in types }.takeIf { it.isNotEmpty() }?.let { RecordGroup(heading, it) }
    }

private val RECORD_GROUPS = listOf(
    "Albums" to setOf(ReleaseType.ALBUM),
    "Singles & EPs" to setOf(ReleaseType.SINGLE, ReleaseType.EP),
    "Soundtracks" to setOf(ReleaseType.SOUNDTRACK),
    "Compilations" to setOf(ReleaseType.COMPILATION),
    "Live Albums" to setOf(ReleaseType.LIVE),
)

/**
 * A heading as a row of its own, [heightPx] tall (a song row's, lighter than the
 * records under it), the words at the list titles' type, white from the list's
 * margin, centred in it. A tap opens what it heads.
 */
@Composable
private fun HeadingRow(text: String, heightPx: Int, onClick: () -> Unit) {
    val type = ROW_TYPE
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(heightPx))
            .rowClickable(onClick = onClick)
            .padding(start = px(ROW_LEAD_PX)),
        contentAlignment = Alignment.CenterStart,
    ) {
        AppText(
            text,
            pxSp(type.titlePx),
            lineHeight = pxSp(type.titleLinePx),
            role = type.titleRole,
            maxLines = 1,
        )
    }
}

/** How far records sit in from the headings over them, without covers. */
private const val RECORD_INDENT_PX = 32

/** How many of the popular songs the artist page shows. */
private const val POPULAR_PREVIEW = 5

/** One of an artist's release groups, to Play or Shuffle: its songs record by record in the page's order, disc and track. */
class GroupPlayScreen(
    sealed: SealedLightActivity,
    private val artist: String,
    private val heading: String,
    private val albums: List<Album>,
) : AmpScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val tracks by App.library.tracks.collectAsState()
        val downloadsOnly by LibraryNav.downloadsOnly.collectAsState()
        val downloadedIds by App.library.downloadedTrackIds.collectAsState()
        val songs = remember(tracks, albums, downloadsOnly, downloadedIds) {
            val place = albums.withIndex().associate { it.value.id to it.index }
            tracks
                .filter { it.albumId in place && (!downloadsOnly || it.id in downloadedIds) }
                .sortedWith(compareBy({ place[it.albumId] }, { it.discNumber ?: 0 }, { it.trackNumber ?: 0 }))
        }
        ListScreen(onBack = { goBack() }, title = heading, subtitle = artist, headingRows = true) {
            ActionList { PlayRows(songs, queueName = "$artist · $heading") }
        }
    }
}



/** The artist page's order as it actually is: Release Type reads as the dates' order where it can't group. */
@Composable
internal fun artistOrderShown(): ArtistAlbumOrder {
    val order by App.settings.artistAlbumOrder.state.collectAsState()
    val albums by App.library.albums.collectAsState()
    return if (order == ArtistAlbumOrder.RELEASE_TYPE && !knowsReleaseTypes(albums)) {
        ArtistAlbumOrder.DATE_RELEASED
    } else {
        order
    }
}

/** Whether any of [albums] is filed as other than an album: what makes grouping by release type mean anything. */
internal fun knowsReleaseTypes(albums: List<Album>): Boolean = albums.any { it.releaseType != ReleaseType.ALBUM }

/** An artist's page's order: by date, or grouped by release type. */
class ArtistAlbumOrderScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val order = artistOrderShown()
        val albums by App.library.albums.collectAsState()
        // Release Type only where the library knows them — see knowsReleaseTypes.
        val options = ArtistAlbumOrder.entries.filter { it != ArtistAlbumOrder.RELEASE_TYPE || knowsReleaseTypes(albums) }
        ListScreen(onBack = { goBack() }, title = "Sort by", subtitle = "Records", headingRows = true) {
            ScrollableList {
                items(options) { option ->
                    TextRow(title = option.label, checked = option == order) {
                        App.scope.launch { App.settings.artistAlbumOrder.set(option) }
                        goBack(Unit)
                    }
                }
            }
        }
    }
}

