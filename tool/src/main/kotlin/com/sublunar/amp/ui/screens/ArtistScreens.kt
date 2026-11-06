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
import com.sublunar.amp.ui.components.rowLeadPx
import com.sublunar.amp.ui.pxSp
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.TALL_ROW_H_PX
import com.sublunar.amp.ui.components.rowType
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightText
import androidx.compose.ui.Modifier
import com.sublunar.amp.ui.px
import androidx.compose.foundation.layout.padding
import com.sublunar.amp.ui.components.LibraryList
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.SelectionHeader
import com.sublunar.amp.ui.components.ShuffleRow
import com.sublunar.amp.ui.components.SplitActionRow
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
     * The two ways into this artist's songs. All Songs holds its place whether or
     * not Popular turns up once fetched (see [SplitActionRow]).
     */
    @Composable
    private fun SongShortcuts(hasPopular: Boolean, tall: Boolean) {
        SplitActionRow(
            leftIcon = AppIcons.FormatListBulleted,
            leftLabel = "All Songs",
            onLeft = { go { ArtistSongsScreen(it, name, credit = credit) } },
            rightIcon = if (hasPopular) AppIcons.Whatshot else null,
            rightLabel = "Popular",
            onRight = if (hasPopular) {
                { go { ArtistSongsScreen(it, name, popular = true, credit = credit) } }
            } else {
                null
            },
            tallList = tall,
        )
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
        var hasPopular by remember(name) { mutableStateOf(false) }
        LaunchedEffect(name, downloadsOnly, downloadedIds) {
            val top = App.library.topSongsForArtist(name)
            hasPopular = if (downloadsOnly) top.any { it.id in downloadedIds } else top.isNotEmpty()
        }

        val grid = App.artistAlbumGrid.collectAsState().value
        // TRIAL (Tall Artist Records): five records to the page.
        val tallRecords = App.settings.tallArtistRecords.state.collectAsState().value
        val order by App.settings.artistAlbumOrder.state.collectAsState()
        // By name as the Albums tab sorts by title; otherwise the dates' order.
        val ordered = remember(artistAlbums, order) {
            if (order == ArtistAlbumOrder.NAME) artistAlbums.sortedBy { nameKey(it.title) } else artistAlbums
        }
        // Grouped only where the library knows release types (see
        // knowsReleaseTypes); otherwise the records keep their dates' order.
        // Singles and EPs as one, the dates' order in each group, and no label
        // where every record is an album.
        val groups = remember(artistAlbums, albums, order) {
            if (order == ArtistAlbumOrder.RELEASE_TYPE && knowsReleaseTypes(albums)) {
                recordGroups(artistAlbums).takeUnless { it.singleOrNull()?.heading == ALBUMS_GROUP }
            } else {
                null
            }
        }
        val shown = remember(groups, ordered) { groups?.flatMap { it.albums } ?: ordered }

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
                            ReleaseGroupLabel(group.heading.orEmpty(), first = start == 0, tall = false)
                        }
                    }
                }
                AlbumGrid(
                    albums = shown,
                    headings = headings,
                    onOpen = { album -> openAlbum(album.id, Parent.Here) },
                    onLongPress = { album -> go { AlbumActionsScreen(it, album.id, onArtistPage = name) } },
                    // One anchor per artist, and one header item: the song shortcuts,
                    // spanning the row so they read as a header over the records.
                    state = rememberGridAnchor("artist-albums:$name/grid", headerCount = 1),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SongShortcuts(hasPopular, tall = false)
                    }
                }
                return@LibrarySubPage
            }
            LibraryList(
                anchor = "artist-albums:$name",
                headerCount = 1,
            ) {
                // A discography: liking is on the artist's long-press, shuffling on
                // the song lists.
                item { SongShortcuts(hasPopular, tall = tallRecords) }
                val sections = groups ?: listOf(RecordGroup(null, ordered))
                sections.forEach { group ->
                    if (group.heading != null) {
                        item(key = "group-${group.heading}") {
                            ReleaseGroupLabel(
                                group.heading,
                                first = group == sections.first(),
                                tall = tallRecords,
                            )
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
                            tall = tallRecords,
                        )
                    }
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

private const val ALBUMS_GROUP = "Albums"

private val RECORD_GROUPS = listOf(
    ALBUMS_GROUP to setOf(ReleaseType.ALBUM),
    "Singles & EPs" to setOf(ReleaseType.SINGLE, ReleaseType.EP),
    "Soundtracks" to setOf(ReleaseType.SOUNDTRACK),
    "Compilations" to setOf(ReleaseType.COMPILATION),
    "Live Albums" to setOf(ReleaseType.LIVE),
)

/**
 * A group's name as the SDK's Weather settings set a setting's: the SDK's own
 * Detail text, from the covers' edge, straight on what it names. Not a button. Above it, the two 0.75-unit paddings that part
 * Weather's rows, less the 12px the cover already keeps above its own row's end.
 */
@Composable
private fun ReleaseGroupLabel(
    text: String,
    first: Boolean,
    /** TRIAL (Tall Artist Records): the records' rows are the tall ones. */
    tall: Boolean = false,
) {
    // TRIAL (Heading Row Labels): a row of its own, half a record's height, the
    // name at the records' titles' type, centred in it.
    val headingRow = App.settings.headingRowLabels.state.collectAsState().value
    val artless = App.hideListArtwork.collectAsState().value
    if (headingRow) {
        val type = rowType(artless = artless)
        val pitchPx = if (tall && !artless) TALL_ROW_H_PX else type.rowPx
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(px(maxOf(pitchPx / 2, type.titleLinePx)))
                .padding(start = px(rowLeadPx())),
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
        return
    }
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        maxLines = 1,
        modifier = Modifier.padding(
            start = px(rowLeadPx()),
            top = px(if (first) GROUP_LABEL_FIRST_TOP_PX else GROUP_LABEL_TOP_PX),
        ),
    )
}

private const val GROUP_LABEL_TOP_PX = 48
/**
 * None under the song shortcuts: their row already leaves about 56px under its
 * words, so the first label sits as far down as the ones between groups.
 */
private const val GROUP_LABEL_FIRST_TOP_PX = 0


/** Whether any of [albums] is filed as other than an album: what makes grouping by release type mean anything. */
internal fun knowsReleaseTypes(albums: List<Album>): Boolean = albums.any { it.releaseType != ReleaseType.ALBUM }

/** An artist's page's order: by date, or grouped by release type. */
class ArtistAlbumOrderScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val order by App.settings.artistAlbumOrder.state.collectAsState()
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

