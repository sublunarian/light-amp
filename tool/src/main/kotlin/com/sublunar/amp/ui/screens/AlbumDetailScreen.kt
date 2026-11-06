package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import com.sublunar.amp.App
import com.sublunar.amp.art.ArtworkNeed
import com.sublunar.amp.data.Album
import com.sublunar.amp.data.Track
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.ArtworkTile
import com.sublunar.amp.ui.components.CenteredNote
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.NumberedRow
import com.sublunar.amp.ui.components.ROW_GAP_PX
import com.sublunar.amp.ui.components.rowLeadPx
import com.sublunar.amp.ui.components.ROW_TITLE_LINE_PX
import com.sublunar.amp.ui.components.ROW_TITLE_PX
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.SelectionHeader
import com.sublunar.amp.ui.components.TextRole
import com.sublunar.amp.ui.components.formatRunTime
import com.sublunar.amp.ui.components.formatTime
import com.sublunar.amp.ui.components.numberedRowTopPx
import com.sublunar.amp.ui.components.rememberArtworkLoad
import com.sublunar.amp.ui.components.rememberListAnchor
import com.sublunar.amp.ui.components.rememberNumberColumn
import com.sublunar.amp.ui.components.rememberSelection
import com.sublunar.amp.ui.components.rowClickable
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.SealedLightActivity

class AlbumDetailScreen(
    sealed: SealedLightActivity,
    private val albumId: String,
) : AmpScreen<Unit>(sealed) {

    /** On the screen, which outlives its composition (the SDK disposes it under a pushed page). */
    private var refreshed = false

    @Composable
    override fun Content() {
        val albums by App.library.albums.collectAsState()
        val album = remember(albums, albumId) { albums.firstOrNull { it.id == albumId } }
        val current by App.playback.currentTrack.collectAsState()
        val artworkHidden by App.hideArtwork.collectAsState()
        val downloadedIds by App.library.downloadedTrackIds.collectAsState()
        val downloadsOnly by LibraryNav.downloadsOnly.collectAsState()

        var tracks by remember(albumId) { mutableStateOf<List<Track>?>(null) }
        LaunchedEffect(albumId) {
            tracks = App.library.getAlbumTracks(albumId)
            // Once per visit, not on every return from a page opened over it.
            if (!refreshed) {
                refreshed = true
                App.library.refreshAlbumTracks(albumId)?.let { tracks = it }
            }
        }

        val selection = rememberSelection("album:$albumId")
        // A row above the tracks only where there is a card: an album opened
        // from a playlist or a radio track can be missing from the album list.
        val card = album?.takeIf { !selection.active }
        val listState = rememberListAnchor("album:$albumId", headerCount = if (card != null) 1 else 0)
        // The card names the album, and the header takes the name only once the
        // card's title has scrolled up under it. Unmeasured counts as never
        // reached, so the header doesn't flash the name first.
        var cardTitleBottomPx by remember(albumId) { mutableStateOf(Int.MAX_VALUE) }
        val headerTitleShown by remember(card) {
            derivedStateOf {
                card == null || listState.firstVisibleItemIndex > 0 ||
                    listState.firstVisibleItemScrollOffset >= cardTitleBottomPx
            }
        }

        LibrarySubPage {
            if (selection.active) {
                SelectionHeader(selection) {
                    openSelectionActions(
                        selection.pick(tracks.orEmpty()) { it.id },
                        selection,
                    )
                }
            } else {
                AppHeader(
                    onBack = { goBack() },
                    // The album's name alone: the artist is in the card below.
                    title = album?.title ?: "Album",
                    // The album's own menu (a record has no order to choose), the
                    // same as a long-press on its row.
                    rightAction = HeaderAction(
                        AppIcons.MoreVert,
                        onLongClick = { go { SettingsScreen(it) } },
                    ) {
                        go { AlbumActionsScreen(it, albumId) }
                    },
                    titleShown = headerTitleShown,
                )
            }

            // Reached from Downloads: what is on the phone, numbered as on the record.
            val list = remember(tracks, downloadsOnly, downloadedIds) {
                if (downloadsOnly) tracks?.filter { it.id in downloadedIds } else tracks
            }
            if (list == null) {
                CenteredNote("Loading…")
            } else {
                // Credits show only where the tracks' own artists vary, and then on
                // every track. Judged against each other, not the album's artist: a
                // classical record's tracks can all carry the composer while the
                // album credits the performer.
                val varied = remember(list) {
                    list.map { it.artist.trim().lowercase() }.distinct().size > 1
                }
                val numbers = remember(list) { list.mapIndexed { i, t -> t.trackNumber ?: (i + 1) } }
                val numberColumn = rememberNumberColumn(numbers)
                ScrollableList(state = listState) {
                    if (card != null) {
                        item {
                            if (artworkHidden) {
                                TextCard(card) { cardTitleBottomPx = it }
                            } else {
                                AlbumCard(card) { cardTitleBottomPx = it }
                            }
                        }
                    }
                    itemsIndexed(list, key = { _, t -> t.id }) { index, track ->
                        NumberedRow(
                            number = numbers[index],
                            title = track.title,
                            subtitle = if (varied) track.artist else "",
                            detail = formatTime(track.durationMs),
                            current = current?.id == track.id,
                            selected = if (selection.active) track.id in selection.selected else null,
                            coverArtId = track.coverArtId,
                            numberColumn = numberColumn,
                            downloaded = track.id in downloadedIds,
                            onClick = {
                                if (selection.active) {
                                    selection.toggle(track.id)
                                } else {
                                    playFromRow(list, index)
                                }
                            },
                            onLongClick = {
                                if (!selection.active) openTrackActions(track.id, selection, onAlbumPage = albumId)
                            },
                        )
                    }
                    // The song count and running time close the list; the card keeps the year.
                    if (list.isNotEmpty() && !selection.active) {
                        item {
                            AppText(
                                albumTotals(album, list),
                                pxSp(CARD_SUB_PX),
                                lineHeight = pxSp(CARD_SUB_LINE_PX),
                                dim = true,
                                maxLines = 1,
                                exact = true,
                                modifier = Modifier.padding(
                                    start = px(rowLeadPx()),
                                    top = px(TOTALS_TOP_PX),
                                    bottom = px(TOTALS_BOTTOM_PX),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * The album's name over [maxLines], cut off past them: at the header's size
     * beside a cover, a step up from it with artwork off ([large]), where the
     * name has the page's top to itself. Its bottom edge is where the header
     * takes over; [onLines] reports how many lines it took.
     */
    @Composable
    private fun CardTitle(
        title: String,
        centred: Boolean,
        maxLines: Int,
        onTitleBottom: (Int) -> Unit,
        large: Boolean = false,
        onLines: (Int) -> Unit = {},
    ) {
        AppText(
            title,
            pxSp(if (large) LightType.COPY_PX else ROW_TITLE_PX),
            lineHeight = pxSp(if (large) LightType.COPY_LINE_PX else ROW_TITLE_LINE_PX),
            role = if (large) TextRole.Copy else TextRole.Fine,
            maxLines = maxLines,
            align = if (centred) TextAlign.Center else null,
            exact = true,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { onTitleBottom(it.boundsInParent().bottom.toInt()) },
            onTextLayout = { onLines(it.lineCount) },
        )
    }

    /**
     * The record at the head of its track list: cover and what it is. At least
     * the cover's height, taller only for a title and credit that run past it. A
     * record with no cover has its text, centred, take the cover's place. With
     * artwork turned off, [TextCard] instead. Play, shuffle, like and rating are
     * in its ⋮ menu.
     */
    @Composable
    private fun AlbumCard(album: Album, onTitleBottom: (Int) -> Unit) {
        // Loaded here rather than in the tile, so a record with no cover can go
        // without the empty square.
        val cardArtPx = with(LocalDensity.current) { px(CARD_ART_PX).roundToPx() }
        val cover = rememberArtworkLoad(album.coverArtId, cardArtPx, ArtworkNeed.FOCUSED)
        val coverless = cover.settled && cover.image == null
        val centred = coverless
        // The title has first claim on the room: a title on its last line
        // leaves the artist one.
        var titleLines by remember(album.id) { mutableStateOf(1) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = px(cardGapPx())),
        ) {
            // The sleeve starts where every list's covers start, after the
            // leading slot; with none, the text does.
            Spacer(Modifier.width(px(rowLeadPx())))
            if (!coverless) {
                ArtworkTile(
                    cover.image,
                    size = px(CARD_ART_PX),
                    fallback = AppIcons.Album,
                    // Held, not tapped, like every artwork in the app: a tap at the
                    // top of a list scrolled past constantly would open it by accident.
                    modifier = Modifier.rowClickable(
                        onClick = {},
                        onLongClick = { go { AlbumArtworkScreen(it, album.id) } },
                    ),
                )
                Spacer(Modifier.width(px(ROW_GAP_PX)))
            }
            Column(
                modifier = Modifier.weight(1f).heightIn(min = px(CARD_ART_PX)),
                horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                CardTitle(
                    album.title,
                    centred = centred,
                    maxLines = CARD_TITLE_LINES,
                    onTitleBottom = onTitleBottom,
                ) { titleLines = it }
                Spacer(Modifier.height(px(CARD_LINE_GAP_PX)))
                AppText(
                    album.artist,
                    pxSp(CARD_SUB_PX),
                    lineHeight = pxSp(CARD_SUB_LINE_PX),
                    role = TextRole.Copy,
                    dim = true,
                    align = if (centred) TextAlign.Center else null,
                    maxLines = if (titleLines >= CARD_TITLE_LINES) 1 else CARD_ARTIST_LINES,
                    exact = true,
                )
                val year = album.year?.toString().orEmpty()
                if (year.isNotEmpty()) {
                    Spacer(Modifier.height(px(CARD_LINE_GAP_PX)))
                    AppText(
                        year,
                        pxSp(CARD_SUB_PX),
                        lineHeight = pxSp(CARD_SUB_LINE_PX),
                        dim = true,
                        align = if (centred) TextAlign.Center else null,
                        maxLines = 1,
                        exact = true,
                    )
                }
            }
        }
    }

    /**
     * The card with artwork turned off, centred: no cover's height to keep to, so
     * the title and credit wrap as far as they need, the credit a step up from
     * the one beside a cover, as the title is.
     */
    @Composable
    private fun TextCard(album: Album, onTitleBottom: (Int) -> Unit) {
        val centred = true
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = px(rowLeadPx()), bottom = px(cardGapPx())),
            horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            CardTitle(album.title, centred = centred, maxLines = Int.MAX_VALUE, onTitleBottom = onTitleBottom, large = true)
            Spacer(Modifier.height(px(TEXT_CARD_LINE_GAP_PX)))
            AppText(
                album.artist,
                pxSp(LightType.FINE_PX),
                lineHeight = pxSp(LightType.FINE_LINE_PX),
                role = TextRole.Fine,
                dim = true,
                align = if (centred) TextAlign.Center else null,
                exact = true,
            )
            val year = album.year?.toString().orEmpty()
            if (year.isNotEmpty()) {
                Spacer(Modifier.height(px(TEXT_CARD_LINE_GAP_PX)))
                AppText(
                    year,
                    pxSp(CARD_SUB_PX),
                    lineHeight = pxSp(CARD_SUB_LINE_PX),
                    dim = true,
                    align = if (centred) TextAlign.Center else null,
                    maxLines = 1,
                    exact = true,
                )
            }
        }
    }

    /** Under the card, so its bottom edge sits [CARD_TO_TRACK_TEXT_PX] above the first track's lines. */
    private fun cardGapPx(): Int = CARD_TO_TRACK_TEXT_PX - numberedRowTopPx()

    /** N songs · total running time, skipping a length that isn't known. */
    private fun albumTotals(album: Album?, tracks: List<Track>): String {
        val songs = if (tracks.isNotEmpty() || album == null) tracks.size else album.songCount
        val lengthMs = if (tracks.isNotEmpty() || album == null) tracks.sumOf { it.durationMs } else album.durationMs
        return listOfNotNull(
            "$songs ${if (songs == 1) "song" else "songs"}",
            formatRunTime(lengthMs).takeIf { lengthMs > 0 },
        ).joinToString(" · ")
    }
}

/** The song count and running time's line, under the last track. */
private const val TOTALS_TOP_PX = 32
private const val TOTALS_BOTTOM_PX = 48

/** The record's own block at the head of its list. */
private const val CARD_ART_PX = 320


/** From the card's bottom edge (the cover, or text that runs past it) to the first track's lines. */
private const val CARD_TO_TRACK_TEXT_PX = 60

/**
 * Beside a cover, the title wraps this far and the credit this far before each
 * is cut off, the credit to one line under a title that takes all of its. With
 * artwork off, neither is cut off.
 */
private const val CARD_TITLE_LINES = 3
private const val CARD_ARTIST_LINES = 2

/** The card's credit and year: the SDK's detail size on its natural line. */
private const val CARD_SUB_PX = LightType.DETAIL_PX
private const val CARD_SUB_LINE_PX = 50

/**
 * The same air between title, credit and year: the leading of the title's own
 * SDK type (its line less its size), fine's beside a cover and copy's with artwork off.
 */
private const val CARD_LINE_GAP_PX = LightType.FINE_LINE_PX - LightType.FINE_PX
private const val TEXT_CARD_LINE_GAP_PX = LightType.COPY_LINE_PX - LightType.COPY_PX

