package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Spacer
import com.sublunar.amp.ui.components.LIST_LEAD_PX
import androidx.compose.runtime.SideEffect
import com.sublunar.amp.ui.components.rememberListAnchor
import com.sublunar.amp.ui.components.LIST_EDGE_PX
import com.sublunar.amp.ui.components.SCROLLBAR_LANE_PX
import com.sublunar.amp.ui.components.TextRole
import com.sublunar.amp.ui.LightType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.derivedStateOf
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
import com.sublunar.amp.ui.components.CentredPlayRow
import com.sublunar.amp.ui.components.ROW_LEAD_PX
import androidx.compose.foundation.layout.Column
import com.sublunar.amp.ui.components.rowClickable
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
import com.sublunar.amp.ui.components.indexLetterOf
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
     * Popular, a heading that opens them all; under it its first
     * [POPULAR_PREVIEW] songs to play from, each ranked on the list's 72px line
     * with the song set in after it, one line each and no cover: a short list
     * to start from, not a page of its own. The heading
     * stands on that line too, or with the release-type headings where those
     * show ([grouped]). Nothing where the server has no such list, or before it
     * has answered.
     */
    @Composable
    private fun PopularPreview(popular: List<Track>, grouped: Boolean) {
        if (popular.isEmpty()) return
        // Tight over its songs, with as much air under its words as each song has
        // above its own, so heading and songs read at one even spacing.
        val line = ROW_TYPE.titleLinePx
        val headingPx = (ROW_TYPE.rowPx - line) / 2 + line + (POPULAR_ROW_PX - line) / 2
        HeadingRow(
            "Popular Songs",
            leadPx = if (grouped) ROW_LEAD_PX else LIST_LEAD_PX,
            tight = true,
            tightHeightPx = headingPx,
        ) {
            go { ArtistSongsScreen(it, name, popular = true, credit = credit) }
        }
        popular.take(POPULAR_PREVIEW).forEachIndexed { index, track ->
            TrackRow(
                title = track.title,
                subtitle = "",
                coverArtId = track.coverArtId,
                // Under a heading on the list's line, set in further so they read as its own.
                modifier = Modifier.padding(start = px(if (grouped) 0 else POPULAR_SONG_INDENT_PX)),
                // A preview of what the artist is known for, not a list to keep: no download marks.
                downloaded = false,
                artworkHidden = true,
                onClick = { playFromRow(popular, index) },
                onLongClick = { openTrackActions(track.id, null, onArtistPage = name) },
                number = index + 1,
                numberOnLine = true,
                oneLinePx = POPULAR_ROW_PX,
            )
        }
    }

    /**
     * At the head of the records, grouped or not: all their songs to play, each
     * in its half of the screen. All Songs is on the artist's long-press; Popular
     * is at the page's end — see [PopularPreview].
     */
    @Composable
    private fun PlayShortcuts(records: List<Album>) {
        // One block, the gap under it included: a grid cell lays its parts over
        // each other, where a list's item stacks them.
        Column {
            CentredPlayRow(
                onPlay = { playAll(songsInOrderOf(records)) },
                onShuffle = { playAll(shuffled(songsInOrderOf(records))) },
                playLabel = "Play All",
            )
            Spacer(Modifier.height(px(PLAY_ROW_GAP_PX)))
        }
    }

    /** Their songs record by record, as the page lists [records], each in disc and track order. */
    private fun songsInOrderOf(records: List<Album>): List<Track> {
        val place = records.withIndex().associate { it.value.id to it.index }
        return App.library.tracksForArtist(name, credit).sortedWith(
            compareBy({ place[it.albumId] ?: records.size }, { it.discNumber ?: 0 }, { it.trackNumber ?: 0 }),
        )
    }

    private fun playAll(songs: List<Track>) {
        if (songs.isEmpty()) return
        App.playback.playQueue(songs, 0, name = name)
        go { NowPlayingScreen(it) }
    }

    @Composable
    override fun Content() {
        val tracks by App.library.tracks.collectAsState()
        val albums by App.library.albums.collectAsState()
        val artistAlbums = remember(tracks, albums, name) { App.library.albumsForArtist(name, credit) }
        val downloadedAlbums by App.library.downloadedAlbumIds.collectAsState()

        // Empty where the server has none (Navidrome needs Last.fm set up).
        // Not asked for where the source has no such list.
        val offered = App.source.collectAsState().value.supportsPopularSongs
        var topSongs by remember(name) { mutableStateOf<List<Track>>(emptyList()) }
        val showPopular by App.settings.showPopularSongs.state.collectAsState()
        LaunchedEffect(name, offered, showPopular) {
            if (offered && showPopular) topSongs = App.library.topSongsForArtist(name)
        }
        val popular = if (showPopular) topSongs else emptyList()

        val grid = App.artistAlbumGrid.collectAsState().value
        val order by App.settings.artistAlbumOrder.state.collectAsState()
        // By name as the Albums tab sorts by title; otherwise the dates' order.
        val ordered = remember(artistAlbums, order) {
            if (order == ArtistAlbumOrder.NAME) artistAlbums.sortedBy { nameKey(it.title) } else artistAlbums
        }
        // Grouped only where the source knows release types (see
        // MusicSource.supportsReleaseTypes). Singles and EPs as one, the page's
        // order in each group. Every group headed, one alone included, so the
        // page is the same shape before and after the popular tracks come.
        val typed = App.source.collectAsState().value.supportsReleaseTypes
        val grouping = App.settings.groupReleaseTypes.state.collectAsState().value && typed
        val groups = remember(ordered, grouping) { if (grouping) recordGroups(ordered) else null }
        val shown = remember(groups, ordered) { groups?.flatMap { it.albums } ?: ordered }
        // The name at the page's top, the header taking it once it has scrolled
        // up under it, as an album's page does with its card.
        val gridState = rememberGridAnchor("artist-albums:$name/grid")
        val listState = rememberListAnchor("artist-albums:$name", headerCount = 0)
        var nameBottomPx by remember(name) { mutableStateOf(Int.MAX_VALUE) }
        val nameScrolledAway by remember(grid) {
            derivedStateOf {
                if (grid) {
                    gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset >= nameBottomPx
                } else {
                    listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset >= nameBottomPx
                }
            }
        }

        LibrarySubPage {
            AppHeader(
                onBack = { goBack() },
                title = name,
                titleShown = nameScrolledAway,
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
                            HeadingRow(group.heading.orEmpty()) {
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
                    state = gridState,
                    footer = {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column { PopularPreview(popular, grouped = groups != null) }
                        }
                    },
                ) {
                    // The name and Play row as one cell, with none of the grid's gap
                    // between them: where the list has them.
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            ArtistNameCard(name, inList = false) { nameBottomPx = it }
                            PlayShortcuts(shown)
                        }
                    }
                }
                return@LibrarySubPage
            }
            // The bar only while the page moves, as the album grid's.
            ScrollableList(state = listState, fadingBar = true) {
                item(key = "name") { ArtistNameCard(name, inList = true) { nameBottomPx = it } }
                // A discography, the ways into the songs around it: liking is on
                // the artist's long-press.
                item(key = "song-shortcuts") { PlayShortcuts(shown) }
                val sections = groups ?: listOf(RecordGroup(null, ordered))
                sections.forEach { group ->
                    if (group.heading != null) {
                        item(key = "group-${group.heading}") {
                            HeadingRow(group.heading) {
                                go { GroupPlayScreen(it, name, group.heading, group.albums) }
                            }
                        }
                    }
                    items(group.albums, key = { it.id }) { album ->
                        TrackRow(
                            title = album.title,
                            subtitle = album.year?.toString() ?: "",
                            // A record without a year as tall as one with, its title where theirs
                            // are: the titles keep one rhythm down the page.
                            keepSubtitleLine = true,
                            coverArtId = album.coverArtId,
                            fallback = AppIcons.Album,
                            downloaded = album.id in downloadedAlbums,
                            liked = album.liked,
                            onClick = { openAlbum(album.id, Parent.Here) },
                            onLongClick = { go { AlbumActionsScreen(it, album.id, onArtistPage = name) } },
                            tall = true,
                        )
                    }
                }
                item(key = "songs-at-end") { Column { PopularPreview(popular, grouped = groups != null) } }
            }
        }
    }

}

/**
 * The artist's name at the top of their page, centred, a size up from
 * an album's name on its page (Copy, where that is Fine). [inList]: a list keeps the scroll
 * bar's lane on the right, so the name is moved over to centre on the screen.
 * [onBottom] reports where the name's text ends.
 */
@Composable
private fun ArtistNameCard(name: String, inList: Boolean, onBottom: (Int) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = px(if (inList) SCROLLBAR_LANE_PX - LIST_EDGE_PX else 0), bottom = px(ARTIST_NAME_GAP_PX)),
    ) {
        AppText(
            name,
            pxSp(LightType.COPY_PX),
            lineHeight = pxSp(LightType.COPY_LINE_PX),
            role = TextRole.Copy,
            align = TextAlign.Center,
            exact = true,
            // A name that wraps in lines of about even length, not a word left over.
            balanced = true,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { onBottom(it.boundsInParent().bottom.toInt()) },
        )
    }
}

/** Under the name, before the first row: 86px from the name's letters to Play All's, about what an album's card leaves above its first track. Measured on the phone. */
private const val ARTIST_NAME_GAP_PX = 18

/** Under Play All, so the row reads as the name's and the records start a step below: 103px to the first title, 73px above. Measured on the phone. */
private const val PLAY_ROW_GAP_PX = 24

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
                PlayRows(artistTracks, queueName = name)
                QueueRows(artistTracks)
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
        if (!popular) {
            ArtistSongsVisit.view.value = ArtistSongsView()
            ArtistSongsVisit.held.value = ArtistSongsHeld()
        }
    }

    override fun onScreenDestroy() {
        if (!popular) {
            ArtistSongsVisit.view.value = ArtistSongsView()
            ArtistSongsVisit.held.value = ArtistSongsHeld()
        }
    }

    @Composable
    override fun Content() {
        // Fixed for the page, so the branch never flips between compositions.
        val all = if (popular) rememberPopular() else rememberAll()
        val downloadedIds by App.library.downloadedTrackIds.collectAsState()
        // Likes changed since the songs were read: see LibraryRepository.songLikes.
        val songLikes by App.library.songLikes.collectAsState()
        val view by ArtistSongsVisit.view.collectAsState()
        val albums by App.library.albums.collectAsState()
        val songs = remember(all, view, downloadedIds, albums) {
            if (popular || all == null) all else view.applyTo(all, downloadedIds, albums)
        }
        if (!popular) {
            val held = remember(all, downloadedIds) {
                ArtistSongsHeld(all.orEmpty().any { it.liked }, all.orEmpty().any { it.id in downloadedIds })
            }
            SideEffect { ArtistSongsVisit.held.value = held }
        }
        val letters = remember(songs, view.sort) { if (popular) emptyList() else view.lettersOf(songs.orEmpty()) }
        // Popular's menu holds only what this hides, so there is no menu left to open.
        val trimmed = moreMenuTrimmed()
        val hasMenu = !popular || !trimmed
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
                AppHeader(
                    onBack = { goBack() },
                    title = if (filters == null) name else null,
                    titleContent = filters?.let { line -> { TitleCard(name, line) } },
                    rightAction = if (hasMenu) {
                        libraryCornerAction(if (popular) LibraryPage.ARTIST_POPULAR else LibraryPage.ARTIST_SONGS, name)
                    } else {
                        null
                    },
                    fitTitle = true,
                )
            }
            when {
                songs == null -> EmptyState("Loading…")
                popular && songs.isEmpty() -> EmptyState("No popular songs")
                songs.isEmpty() && view.narrows -> EmptyState("No songs match")
                else -> IndexedList(
                    anchor = owner,
                    letters = letters,
                    headerCount = if (selection.active) 0 else 1,
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
                            liked = songLikes[track.id] ?: track.liked,
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
            ArtistSongSort.PLAYED -> narrowed.sortedByDescending { it.lastPlayedMs }
            ArtistSongSort.RATING -> narrowed.sortedByDescending { it.rating }
            ArtistSongSort.ADDED -> {
                val added = albums.associate { it.id to it.createdMs }
                narrowed.sortedByDescending { added[it.albumId] ?: 0L }
            }
        }
    }

    /** Each of [songs]'s letter for the A–Z strip, or none where this order isn't alphabetical. */
    fun lettersOf(songs: List<Track>): List<Char> = when (sort) {
        ArtistSongSort.ALBUM -> songs.map { indexLetterOf(titleKey(it.album)) }
        ArtistSongSort.TITLE -> songs.map { indexLetterOf(titleKey(it.title)) }
        else -> emptyList()
    }

    /** The header's second line: what narrows the list, or null. */
    fun filterLine(): String? =
        listOfNotNull("Favorites".takeIf { liked }, "Downloaded".takeIf { downloaded })
            .takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** The orders an artist's songs can be put in; by album is how they come. */
enum class ArtistSongSort(val label: String) {
    ALBUM("Album"),
    TITLE("Title"),
    ADDED("Recently Added"),
    PLAYED("Recently Played"),
    RATING("Rating"),
}

/** The visit in progress — its More page reads and changes it. */
object ArtistSongsVisit {
    val view = MutableStateFlow(ArtistSongsView())

    /** What the artist's songs hold, whatever the view narrows: where the page's switches have anything to narrow to. */
    val held = MutableStateFlow(ArtistSongsHeld())
}

/** Whether any of an artist's songs are liked, and any downloaded. */
data class ArtistSongsHeld(val liked: Boolean = false, val downloaded: Boolean = false)

/** "Sort by" for an artist's songs: picking one applies it and closes, as the tabs' do. */
class ArtistSongSortScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val view by ArtistSongsVisit.view.collectAsState()
        val supportsRatings = App.source.collectAsState().value.supportsRatings
        val shown = ArtistSongSort.entries.filter { it != ArtistSongSort.RATING || supportsRatings }
        ListScreen(onBack = { goBack() }, title = "Sort by", subtitle = "Songs", headingRows = true) {
            ScrollableList {
                items(shown) { sort ->
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
 * A heading as a row of its own, a song row's height (lighter than the records
 * under it), the words at the list titles' type, white, 40px from the screen's
 * edge where the records under it start at 72. A tap opens what it heads.
 */
@Composable
private fun HeadingRow(
    text: String,
    /** Where the words start: the headings' 40px, or the list's 72px line. */
    leadPx: Int = ROW_LEAD_PX,
    /**
     * A one-line row's height with the words where they were, so the air below
     * is about half the air above: for a heading over one-line rows.
     */
    tight: Boolean = false,
    /** A tight heading's height where its rows set their own, else a one-line row's. */
    tightHeightPx: Int? = null,
    onClick: () -> Unit,
) {
    val type = ROW_TYPE
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(if (tight) tightHeightPx ?: type.oneLinePx else type.rowPx))
            .rowClickable(onClick = onClick)
            .padding(start = px(leadPx), top = px(if (tight) (type.rowPx - type.titleLinePx) / 2 else 0)),
        contentAlignment = if (tight) Alignment.TopStart else Alignment.CenterStart,
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

/** How many of the popular songs the artist page shows. */
private const val POPULAR_PREVIEW = 5


/** Popular's songs set in from its heading where that heading is on the list's 72px line. */
private const val POPULAR_SONG_INDENT_PX = 20

/** Popular's songs this far apart: 61px between titles, where the lists' one-line rows leave 76. */
private const val POPULAR_ROW_PX = 100

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
        val songs = remember(tracks, albums) {
            val place = albums.withIndex().associate { it.value.id to it.index }
            tracks
                .filter { it.albumId in place }
                .sortedWith(compareBy({ place[it.albumId] }, { it.discNumber ?: 0 }, { it.trackNumber ?: 0 }))
        }
        ListScreen(onBack = { goBack() }, title = heading, subtitle = artist, headingRows = true) {
            ActionList { PlayRows(songs, queueName = "$artist · $heading") }
        }
    }
}


/** An artist's page's order: by date or by name. */
class ArtistAlbumOrderScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val order by App.settings.artistAlbumOrder.state.collectAsState()
        ListScreen(onBack = { goBack() }, title = "Sort by", subtitle = "Records", headingRows = true) {
            ScrollableList {
                items(ArtistAlbumOrder.entries) { option ->
                    TextRow(title = option.label, checked = option == order) {
                        App.scope.launch { App.settings.artistAlbumOrder.set(option) }
                        goBack(Unit)
                    }
                }
            }
        }
    }
}

