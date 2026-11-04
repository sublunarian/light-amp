package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import com.sublunar.amp.App
import com.sublunar.amp.art.ArtworkNeed
import com.sublunar.amp.data.Album
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.shuffled
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.appTextStyle
import com.sublunar.amp.ui.components.ArtworkTile
import com.sublunar.amp.ui.components.CenteredNote
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.NumberedRow
import com.sublunar.amp.ui.components.ROW_GAP_PX
import com.sublunar.amp.ui.components.ROW_LEAD_PX
import com.sublunar.amp.ui.components.ROW_TITLE_LINE_PX
import com.sublunar.amp.ui.components.ROW_TITLE_PX
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.SelectionHeader
import com.sublunar.amp.ui.components.TextRole
import com.sublunar.amp.ui.components.appClickable
import com.sublunar.amp.ui.components.formatRunTime
import com.sublunar.amp.ui.components.formatTime
import com.sublunar.amp.ui.components.rememberArtworkLoad
import com.sublunar.amp.ui.components.rememberListAnchor
import com.sublunar.amp.ui.components.rememberSelection
import com.sublunar.amp.ui.components.rowClickable
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.SealedLightActivity
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
                        go { AlbumActionsScreen(it, albumId, fromDetail = true) }
                    },
                    fitTitle = true,
                    titleShown = headerTitleShown,
                )
            }

            val list = tracks
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
                ScrollableList(state = listState) {
                    if (card != null) item { AlbumCard(card, list) { cardTitleBottomPx = it } }
                    itemsIndexed(list, key = { _, t -> t.id }) { index, track ->
                        NumberedRow(
                            number = track.trackNumber ?: (index + 1),
                            title = track.title,
                            subtitle = if (varied) track.artist else "",
                            detail = formatTime(track.durationMs),
                            current = current?.id == track.id,
                            selected = if (selection.active) track.id in selection.selected else null,
                            coverArtId = track.coverArtId,
                            onClick = {
                                if (selection.active) {
                                    selection.toggle(track.id)
                                } else {
                                    App.playback.playQueue(list, index)
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
    }

    /**
     * The album's name, whole over [maxLines]: a name too long for them steps
     * down a pixel at a time, its line with it, as far as [minPx], and is cut off
     * only past that. Its bottom edge is where the header takes over; [onLines]
     * reports how many lines it took.
     */
    @Composable
    private fun CardTitle(
        title: String,
        fullPx: Int,
        fullLinePx: Int,
        minPx: Int,
        role: TextRole,
        maxLines: Int,
        onTitleBottom: (Int) -> Unit,
        onLines: (Int) -> Unit,
    ) {
        BoxWithConstraints {
            val room = constraints.maxWidth
            val sizes = (fullPx downTo minPx).toList()
            val lineFor = { size: Int -> (size * fullLinePx.toFloat() / fullPx).roundToInt() }
            val styles = sizes.map { appTextStyle(pxSp(it), pxSp(lineFor(it)), role, exact = true) }
            val measurer = rememberTextMeasurer()
            val step = remember(title, room, maxLines, styles) {
                styles.indexOfFirst { style ->
                    !measurer.measure(
                        AnnotatedString(title),
                        style = style,
                        maxLines = maxLines,
                        constraints = Constraints(maxWidth = room),
                    ).hasVisualOverflow
                }
            }
            val size = if (step < 0) sizes.last() else sizes[step]
            AppText(
                title,
                pxSp(size),
                lineHeight = pxSp(lineFor(size)),
                role = role,
                maxLines = maxLines,
                exact = true,
                modifier = Modifier.onGloballyPositioned { onTitleBottom(it.boundsInParent().bottom.toInt()) },
                onTextLayout = { onLines(it.lineCount) },
            )
        }
    }

    /**
     * The record at the head of its track list: cover, what it is, and like and
     * shuffle as bare icons (tapping any track already plays the album). Always
     * the cover's height; with no cover to show (covers hidden, or the record
     * has none) the text takes the cover's place, set out the same.
     */
    @Composable
    private fun AlbumCard(album: Album, tracks: List<Track>, onTitleBottom: (Int) -> Unit) {
        // Loaded here rather than in the tile, so a record with no cover can go
        // without the empty square.
        val cardArtPx = with(LocalDensity.current) { px(CARD_ART_PX).roundToPx() }
        val cover = rememberArtworkLoad(album.coverArtId, cardArtPx, ArtworkNeed.FOCUSED)
        val coverless = cover.settled && cover.image == null
        // The title has first claim on the room: a title over more than one
        // line leaves the artist one.
        var titleLines by remember(album.id) { mutableStateOf(1) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(px(CARD_ART_PX))
                .padding(bottom = px(CARD_GAP_PX)),
        ) {
            // The sleeve starts where every list's covers start, after the
            // leading slot; with none, the text does.
            Spacer(Modifier.width(px(ROW_LEAD_PX)))
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
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                CardTitle(
                    album.title,
                    fullPx = ROW_TITLE_PX,
                    fullLinePx = ROW_TITLE_LINE_PX,
                    // No smaller than the credit under it.
                    minPx = CARD_SUB_PX,
                    role = TextRole.Fine,
                    maxLines = CARD_TITLE_LINES,
                    onTitleBottom = onTitleBottom,
                ) { titleLines = it }
                // The credit, then the facts. The credit's lines sit closer than
                // the facts sit below it, so a wrapped name reads as one name. On
                // their natural lines, as asked (the toggle's snap would put them
                // on detail's looser 60px line).
                AppText(
                    album.artist,
                    pxSp(CARD_SUB_PX),
                    lineHeight = pxSp(CARD_SUB_LINE_PX),
                    dim = true,
                    maxLines = if (titleLines > 1) 1 else CARD_ARTIST_LINES,
                    exact = true,
                )
                Spacer(Modifier.height(px(CARD_FACTS_GAP_PX)))
                // Title and credit belong together at the top; the facts are of
                // another kind and sit on the cover's bottom edge.
                AppText(
                    albumFacts(album, tracks),
                    pxSp(CARD_SUB_PX),
                    lineHeight = pxSp(CARD_SUB_LINE_PX),
                    dim = true,
                    maxLines = 1,
                    exact = true,
                )
                Spacer(Modifier.weight(1f))
                CardButtons(album, tracks)
            }
        }
    }

    /** Shuffle first, then the album's like and rating where the source keeps them. */
    @Composable
    private fun CardButtons(album: Album, tracks: List<Track>) {
        val source by App.source.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(
                AppIcons.Shuffle,
                size = px(CARD_ICON_PX),
                modifier = Modifier.appClickable { shuffleAlbum(tracks) },
            )
            if (source.supportsLikes) {
                Spacer(Modifier.width(px(CARD_ICON_GAP_PX)))
                AppIcon(
                    if (album.liked) AppIcons.Favorite else AppIcons.FavoriteBorder,
                    size = px(CARD_ICON_PX),
                    modifier = Modifier.appClickable {
                        App.scope.launch { App.library.setAlbumLiked(album, !album.liked) }
                    },
                )
            }
            if (source.supportsRatings) {
                Spacer(Modifier.width(px(CARD_ICON_GAP_PX)))
                AppIcon(
                    if (album.rating > 0) AppIcons.Star else AppIcons.StarBorder,
                    size = px(CARD_ICON_PX),
                    modifier = Modifier.appClickable {
                        go { RatingScreen(it, album.id, album.title, album.rating, isAlbum = true) }
                    },
                )
            }
        }
    }

    private fun shuffleAlbum(tracks: List<Track>) {
        App.playback.playQueue(shuffled(tracks), 0)
        go { NowPlayingScreen(it) }
    }

    /** Year · N songs · total running time, skipping whatever isn't known. */
    private fun albumFacts(album: Album, tracks: List<Track>): String {
        val songs = if (tracks.isNotEmpty()) tracks.size else album.songCount
        val lengthMs = if (tracks.isNotEmpty()) tracks.sumOf { it.durationMs } else album.durationMs
        return listOfNotNull(
            album.year?.toString(),
            "$songs ${if (songs == 1) "song" else "songs"}",
            formatRunTime(lengthMs).takeIf { lengthMs > 0 },
        ).joinToString(" · ")
    }
}

/** The record's own block at the head of its list. */
private const val CARD_ART_PX = 300
private const val CARD_GAP_PX = 24
private const val CARD_ICON_PX = 60
private const val CARD_ICON_GAP_PX = 66

/** The credit wraps this far beside a one-line title before it's cut off; one line beside a longer one. */
private const val CARD_ARTIST_LINES = 2

/** The title takes up to two lines, shrinking to fit them before it is cut off. */
private const val CARD_TITLE_LINES = 2

/** The card's credit and facts: the SDK's detail size on its natural line. */
private const val CARD_SUB_PX = LightType.DETAIL_PX
private const val CARD_SUB_LINE_PX = 50

/** Extra air above the facts, so a wrapped credit's lines sit closer than the facts do. */
private const val CARD_FACTS_GAP_PX = 10

