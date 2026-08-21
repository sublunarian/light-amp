package com.sublunar.amp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sublunar.amp.App
import com.sublunar.amp.data.ArtworkMode
import com.sublunar.amp.data.LyricLine
import com.sublunar.amp.data.LyricsRepository
import com.sublunar.amp.data.LastSection
import com.sublunar.amp.data.RepeatMode
import com.sublunar.amp.data.Track
import com.sublunar.amp.ui.PlayerTheme
import com.sublunar.amp.ui.components.AppArtwork
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.AppProgressBar
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import com.sublunar.amp.ui.components.TextRole
import com.sublunar.amp.ui.components.rememberArtwork
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.ListScrollBar
import com.sublunar.amp.ui.components.ROW_TITLE_PX
import com.sublunar.amp.ui.components.ROW_TITLE_LINE_PX
import com.sublunar.amp.ui.components.ROW_ART_PX
import com.sublunar.amp.ui.components.ROW_GAP_PX
import com.sublunar.amp.ui.components.ROW_SUB_PX
import com.sublunar.amp.ui.components.ROW_SUB_LINE_PX
import com.sublunar.amp.ui.components.SelectionHeader
import com.sublunar.amp.ui.components.SelectionState
import com.sublunar.amp.ui.components.rememberSelection
import com.sublunar.amp.ui.components.TitleCard
import com.sublunar.amp.ui.components.HEADER_BAR_PX
import com.sublunar.amp.ui.components.formatTime
import com.sublunar.amp.ui.n
import com.sublunar.amp.ui.nSp
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.sublunar.amp.ui.components.rowClickable
import com.sublunar.amp.ui.components.slowLongPress
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightThemeTokens
import com.sublunar.amp.ui.components.appClickable
import kotlinx.coroutines.launch

/** Where the active synced-lyric line sits in the pane, as a fraction of its height. */
private const val LYRIC_ANCHOR = 0.38f

/**
 * Shared width for the trailing control on a queue row, so the drag handles line
 * up down the right edge of the list.
 */
private val QUEUE_TRAILING_SLOT = n(20)

enum class NpView { ARTWORK, QUEUE }

/**
 * Which pane Now Playing shows, and whether the lyrics overlay is up.
 *
 * Held here rather than on the screen instance so other screens can drive it —
 * the full-width artwork view needs to be able to send the user to the queue —
 * and so it survives navigating away and back.
 */
object NowPlayingNav {
    val view = mutableStateOf(NpView.ARTWORK)
    val lyricsOverlay = mutableStateOf(false)

    /**
     * The cover behind the controls — the Cover Only full-artwork layout.
     *
     * Not a screen of its own, deliberately. "The same page with the sleeve
     * behind it" is only true if it *is* the same page: any second copy of the
     * header, seek line and transport would have to be kept in step with this
     * one by hand, and the two earlier attempts at that were both rejected for
     * drifting out of place. So the player draws itself exactly as it always
     * does and this only changes what is behind it.
     */
    val coverOnly = mutableStateOf(false)

    /**
     * Whether the controls are up while the cover fills the screen.
     *
     * Deliberately *not* persistent, unlike [coverOnly]: opening the player puts
     * them back. Hiding them is something you do to look at a sleeve for a
     * moment, not a preference about the page — and a player that opens with no
     * controls on it reads as a fault, with the tap that recovers them
     * discoverable only to someone who already knows it is there. Held here
     * rather than in the screen so that toggling it survives a look at the queue
     * pane; the screen resets it on each open.
     */
    val coverChrome = mutableStateOf(true)
}

private fun nextView(v: NpView): NpView = when (v) {
    NpView.ARTWORK -> NpView.QUEUE
    NpView.QUEUE -> NpView.ARTWORK
}

class NowPlayingScreen(
    sealed: SealedLightActivity,
    /** Set only by callers that deliberately want the queue, e.g. full-screen art. */
    openOnQueue: Boolean = false,
) : SimpleLightScreen<Unit>(sealed) {

    /**
     * The player is the third of the bar's destinations, so it says so while it
     * is up and hands the section back on the way out — see [LastSection].
     *
     * On the way out rather than leaving it set: what is revealed underneath is
     * a library page or the results, and only this screen knows the moment it
     * stops being the one showing.
     */
    override fun willShow() {
        LibraryNav.record(LastSection.NOW_PLAYING)
    }

    override fun willHide() {
        LibraryNav.record(
            if (LibraryNav.searchActive.value) LastSection.SEARCH else LastSection.LIBRARY,
        )
    }

    init {
        // Opening Now Playing always lands on the playback controls. The view is
        // remembered across navigation so the queue survives a trip into track
        // options, but a fresh open should never drop the user in the list.
        NowPlayingNav.view.value = if (openOnQueue) NpView.QUEUE else NpView.ARTWORK
        // The cover is deliberately *not* reset with it. Which pane you are on is
        // a step in a task — you opened the queue to do something, and coming
        // back to the player later means coming back to the controls. Whether
        // the sleeve fills the screen is a preference about how the player
        // looks, and a preference that undoes itself every time you leave the
        // page is one you have to keep setting.
        //
        // Having *hidden* the controls is neither: it is a moment's look at a
        // sleeve, so opening the player always brings them back — including
        // into Cover Only, where arriving from the library with no controls and
        // no visible way to get them reads as a page that has failed to draw.
        // Hiding them is what the long press and the menu are for.
        NowPlayingNav.coverChrome.value = true
    }

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val track by App.playback.currentTrack.collectAsState()
        val queue by App.playback.queue.collectAsState()
        val index by App.playback.index.collectAsState()
        val view = NowPlayingNav.view.value

        PlayerTheme {
            val current = track
            if (current == null) {
                NothingPlaying()
                return@PlayerTheme
            }
            when (view) {
                NpView.ARTWORK -> ArtPlayer(current)
                NpView.QUEUE -> QueuePlayer(current, queue, index)
            }
        }
    }

    /** Reached only by opening the player before anything has been played. */
    @Composable
    private fun NothingPlaying() {
        Column(modifier = Modifier.fillMaxSize()) {
            // Same button as the player itself, so the way out doesn't move
            // depending on whether anything happens to be playing.
            AppHeader(
                onBack = { goBack() },
                title = "Now Playing",
            )
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppText("Nothing playing", nSp(22))
            }
        }
    }

    @Composable
    private fun LyricsOverlay(
        current: Track,
        scrim: androidx.compose.ui.graphics.Color,
        modifier: Modifier,
    ) {
        val karaoke by App.settings.karaokeLyrics.collectAsState(initial = true)
        val position by App.playback.positionMs.collectAsState()
        var outcome by remember(current.id) { mutableStateOf<LyricsRepository.Outcome?>(null) }
        var loading by remember(current.id) { mutableStateOf(true) }
        LaunchedEffect(current.id) {
            loading = true
            outcome = LyricsRepository.forTrack(current, App.serverClient.value)
            loading = false
        }
        val lyrics = (outcome as? LyricsRepository.Outcome.Found)?.lyrics
        val lines = lyrics?.lines ?: emptyList()
        val synced = (lyrics?.synced == true) && karaoke
        val activeIndex = if (synced) indexForTime(lines, position) else -1

        Box(modifier = modifier.fillMaxWidth().background(scrim)) {
            when {
                loading -> CenteredText("Loading lyrics…")
                outcome == LyricsRepository.Outcome.Unavailable ->
                    CenteredText("Couldn't reach the lyrics service")
                lines.isEmpty() -> CenteredText("No lyrics")
                else -> {
                    val listState = rememberLazyListState()
                    // Hold the active line ~38% down the pane (matching the old
                    // app) instead of pinning it to the very top, and pad the
                    // bottom so the closing lines can still reach that anchor.
                    LaunchedEffect(activeIndex, synced) {
                        if (!synced || activeIndex < 0) return@LaunchedEffect
                        val viewport = listState.layoutInfo.viewportSize.height
                        if (viewport <= 0) return@LaunchedEffect
                        listState.animateScrollToItem(
                            activeIndex,
                            -(viewport * LYRIC_ANCHOR).toInt(),
                        )
                    }
                    val tailPadding = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = n(20),
                            end = n(20),
                            top = n(18),
                            bottom = if (synced) tailPadding else n(18),
                        ),
                    ) {
                        itemsIndexed(lines) { i, line ->
                            AppText(
                                text = line.text,
                                size = nSp(17),
                                lineHeight = nSp(22),
                                align = TextAlign.Center,
                                dim = synced && i != activeIndex,
                                modifier = Modifier.fillMaxWidth().padding(vertical = n(2)),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CenteredText(text: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AppText(text, nSp(15), dim = true)
        }
    }

    private fun indexForTime(lines: List<LyricLine>, positionMs: Long): Int {
        var result = -1
        for (i in lines.indices) {
            val t = lines[i].timeMs ?: break
            if (t > positionMs) break
            result = i
        }
        return result
    }

    @Composable
    private fun QueueView(
        queue: List<Track>,
        index: Int,
        selection: SelectionState,
        modifier: Modifier = Modifier,
        horizontalPadding: Dp = n(16),
        listState: LazyListState = rememberLazyListState(),
    ) {
        var draggingIndex by remember { mutableStateOf<Int?>(null) }
        var dragOffsetY by remember { mutableStateOf(0f) }
        val rowPx = with(LocalDensity.current) { px(160).toPx() }

        // The playing track stays at the top: what's already gone is the least
        // interesting part of a queue. Opening the page jumps there outright,
        // while a track ending animates, so the list is seen to move rather than
        // appearing to have been somewhere else all along.
        var anchored by remember { mutableStateOf(false) }
        LaunchedEffect(index) {
            val target = index.coerceAtLeast(0)
            if (anchored) listState.animateScrollToItem(target) else listState.scrollToItem(target)
            anchored = true
        }

        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
        ) {
            itemsIndexed(queue, key = { i, t -> "$i-${t.id}" }) { i, track ->
                val isCurrent = i == index
                val isPast = i < index
                val isDragging = i == draggingIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(px(160))
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragOffsetY else 0f }
                        .alpha(if (isPast) 0.5f else 1f)
                        .rowClickable(
                            // Tapping the playing row is still play/pause — that
                            // was always the row's job; the button beside it only
                            // said so twice.
                            onClick = {
                                when {
                                    selection.active -> selection.toggle(track.id)
                                    isCurrent -> App.playback.togglePlayPause()
                                    else -> App.playback.jumpTo(i)
                                }
                            },
                            onLongClick = { if (!selection.active) openQueueOptions(i, selection) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selection.active) {
                        val checked = track.id in selection.selected
                        Box(Modifier.size(px(ROW_ART_PX)), contentAlignment = Alignment.Center) {
                            AppIcon(
                                if (checked) AppIcons.Selected else AppIcons.Unselected,
                                size = n(26),
                                tint = if (checked) {
                                    LightThemeTokens.colors.content
                                } else {
                                    LightThemeTokens.colors.contentSecondary
                                },
                            )
                        }
                    } else {
                        AppArtwork(track.coverArtId, size = px(ROW_ART_PX))
                    }
                    Spacer(Modifier.width(px(ROW_GAP_PX)))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isCurrent) {
                                AppIcon(AppIcons.Waveform, size = n(18))
                                Spacer(Modifier.width(n(6)))
                            }
                            AppText(
                                track.title,
                                pxSp(ROW_TITLE_PX),
                                lineHeight = pxSp(ROW_TITLE_LINE_PX),
                                maxLines = 1,
                            )
                        }
                        AppText(
                            track.artist,
                            pxSp(ROW_SUB_PX),
                            lineHeight = pxSp(ROW_SUB_LINE_PX),
                            dim = true,
                            maxLines = 1,
                        )
                    }
                    // Upcoming rows get a drag handle; drag to reorder within the
                    // upcoming portion of the queue (can't move into history/current).
                    if (!isPast && !isCurrent && !selection.active) {
                        AppIcon(
                            AppIcons.Dehaze,
                            size = n(20),
                            modifier = Modifier.width(QUEUE_TRAILING_SLOT).pointerInput(queue.size, index) {
                                detectDragGestures(
                                    onDragStart = { draggingIndex = i; dragOffsetY = 0f },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffsetY += amount.y
                                    },
                                    onDragEnd = {
                                        val from = draggingIndex
                                        if (from != null && queue.size > index + 1) {
                                            val target = (from + (dragOffsetY / rowPx).roundToInt())
                                                .coerceIn(index + 1, queue.lastIndex)
                                            if (target != from) App.playback.moveInQueue(from, target)
                                        }
                                        draggingIndex = null
                                        dragOffsetY = 0f
                                    },
                                    onDragCancel = { draggingIndex = null; dragOffsetY = 0f },
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ScrubButton(
        icon: ImageVector,
        direction: Int,
        size: androidx.compose.ui.unit.Dp = n(42),
        onSkip: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        Box(
            // Fills the cell so tap-to-skip and hold-to-scrub get the whole square.
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    var scrubbing = false
                    val hold = scope.launch {
                        delay(300)
                        scrubbing = true
                        while (isActive) {
                            val pos = App.playback.positionMs.value
                            val dur = App.playback.durationMs.value
                            if (dur > 0) App.playback.seekTo((pos + direction * 5000L).coerceIn(0L, dur))
                            delay(250)
                        }
                    }
                    waitForUpOrCancellation()
                    hold.cancel()
                    if (!scrubbing) onSkip()
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(icon, size = size)
        }
    }

    // --- Player -------------------------------------------------

    /**
     * A black header over full-bleed artwork, with every control floating on the
     * cover itself.
     *
     * Below the header nothing is stacked: each element is anchored to the bottom
     * of the screen at the exact depth the design gives it — seek line 360px up,
     * clocks 40px under that, transport 240px up, the secondary row 120px up.
     * Stacking would make every one of those depths a consequence of the ones
     * above it, and a single font-metric change would move them all.
     *
     * Cover Only is this same page with the sleeve moved out from its panel to
     * fill everything under the header. Nothing else moves — the controls dim,
     * gain a wash to be read against, and a tap can put them away.
     */
    @Composable
    private fun ArtPlayer(current: Track) {
        val artwork by App.settings.artwork.collectAsState(initial = ArtworkMode.SMALL)
        // The words want the whole panel, so the title goes back to the header
        // it came from rather than sitting behind them.
        val heroTitle = artwork == ArtworkMode.NONE && !NowPlayingNav.lyricsOverlay.value
        // Either of these leaves nothing to put behind the controls: no cover at
        // all, or the lyrics in its place.
        val coverOnly = NowPlayingNav.coverOnly.value &&
            artwork != ArtworkMode.NONE &&
            !NowPlayingNav.lyricsOverlay.value
        // Held in NowPlayingNav, not here, so putting the controls away outlasts
        // a trip to the queue or the library — see NowPlayingNav.coverChrome.
        val chrome = NowPlayingNav.coverChrome.value

        Column(modifier = Modifier.fillMaxSize()) {
            PlayerHeader(
                current,
                left = {
                    // The same chevron the queue view uses, on the shared edge
                    // axis — ArrowBackIos draws its glyph left of centre, which
                    // is what BACK_ICON_BIAS puts right.
                    AppIcon(
                        AppIcons.ArrowBackIos,
                        size = px(BACK_ICON_PX),
                        modifier = Modifier
                            .offset(x = px(BACK_BIAS_PX))
                            .appClickable { goBack() },
                    )
                },
                right = {
                    AppIcon(
                        AppIcons.MoreVert,
                        size = px(HEADER_ICON_PX),
                        modifier = Modifier.appClickable {
                            openOptions(current.id, artwork, coverOnly)
                        },
                    )
                },
                showTitle = !heroTitle,
            )
            Box(modifier = Modifier.fillMaxSize()) {
                Stage(
                    current,
                    fullBleed = coverOnly,
                    // A tap puts the controls away and brings them back; the
                    // long press that opened the cover closes it, which is how
                    // the other two layouts leave as well.
                    onTap = { if (coverOnly) NowPlayingNav.coverChrome.value = !chrome },
                    onLongPress = {
                        NowPlayingNav.coverOnly.value = !coverOnly
                        // Going full-screen means going to the picture, so the
                        // controls step out of its way; a tap brings them back.
                        if (!coverOnly) NowPlayingNav.coverChrome.value = false
                    },
                )
                if (heroTitle) TitleBlock(current)
                if (!coverOnly || chrome) {
                    // On the cover everything steps back a little, but by less
                    // than the secondary row is knocked back on black: there it
                    // is being separated from the transport above it, here it is
                    // being kept legible over a picture, and the second job needs
                    // more of the glyph than the first.
                    val dim = if (coverOnly) ON_COVER_ALPHA else DIM_ALPHA
                    if (coverOnly) ControlScrim()
                    SeekBand(alpha = if (coverOnly) ON_COVER_ALPHA else 1f)
                    TransportRow()
                    SecondaryRow(current, alpha = dim)
                }
            }
        }
    }

    /**
     * The wash the controls sit on once the cover is behind them.
     *
     * There to stop white-on-white, not to dim the picture: it fades out
     * entirely a little above the seek line, so the sleeve is untouched over most
     * of the screen and only the part carrying controls is darkened.
     */
    @Composable
    private fun BoxScope.ControlScrim() {
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(px(SCRIM_H_PX))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            LightThemeTokens.colors.background.copy(alpha = SCRIM_ALPHA),
                        ),
                    ),
                ),
        )
    }

    /**
     * The queue in the big player's own geometry.
     *
     * The controls do not move: seek line, transport and the bottom row stay
     * exactly where they were, and only what is behind them changes from cover to
     * list. The header gives its two squares to shuffle and repeat — the queue is
     * where you set how it will be played — and drops the title card, except
     * while selecting, when it becomes the count and the two selection controls.
     * The list shares the seek line's 80px side inset and stops short of the
     * controls.
     */
    @Composable
    private fun QueuePlayer(current: Track, queue: List<Track>, index: Int) {
        val shuffle by App.playback.shuffle.collectAsState()
        val repeat by App.playback.repeatMode.collectAsState()
        val selection = rememberSelection("queue")

        val background = LightThemeTokens.colors.background

        Box(modifier = Modifier.fillMaxSize().background(background)) {
            if (selection.active) {
                SelectionHeader(
                    selection = selection,
                    onDelete = {
                        App.playback.removeFromQueue(selection.selected)
                        // Stay in selection mode with nothing chosen: pruning a
                        // queue is usually more than one pass, and the X is there.
                        selection.begin()
                    },
                    onConfirm = {
                        openSelectionActions(
                            selection.pick(queue) { it.id },
                            selection,
                            // Everything here is in the queue already, and
                            // downloads are a library job — see the track menu.
                            showAddToQueue = false,
                            showDownload = false,
                        )
                    },
                )
            } else {
                PlayerHeader(
                    current,
                    left = {
                        AppIcon(
                            AppIcons.ArrowBackIos,
                            size = px(BACK_ICON_PX),
                            modifier = Modifier
                                .offset(x = px(BACK_BIAS_PX))
                                .appClickable { NowPlayingNav.view.value = NpView.ARTWORK },
                        )
                    },
                    right = {},
                    // Not the track — that one is on screen already, in the row
                    // marked as playing. This says which page you are on.
                    title = "Queue",
                    // Where in it you are. Only with something playing: a queue
                    // with nothing current has no "of" to answer, and "0 of 12"
                    // would be a position no row is at.
                    subtitle = if (index in queue.indices) {
                        "${index + 1} of ${queue.size}"
                    } else {
                        null
                    },
                )
            }
            val listState = rememberLazyListState()
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = px(QUEUE_TOP_PX))
                    .fillMaxWidth()
                    .height(px(SCREEN_H_PX - QUEUE_TOP_PX - QUEUE_END_PX)),
            ) {
                QueueView(
                    queue,
                    index,
                    selection,
                    Modifier.fillMaxSize(),
                    horizontalPadding = px(INSET_PX),
                    listState = listState,
                )
                // Outside the list's own margins, hard against the screen edge —
                // inside them it sat on top of the drag handles.
                ListScrollBar(listState)
            }
            // Shuffle and repeat as a bar of their own, on the axis the library's
            // tabs use: the queue is where you set how it will be played, and the
            // transport is one tap away on the page this came from.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(px(HEADER_BAR_PX))
                    // Both glyphs' outer edges land where the simplified nav
                    // bar's do — see EDGE_GLYPH_PX. Their slots are the bar's
                    // own square, so the inset is that distance less the slack
                    // the glyph already has inside one.
                    .padding(
                        horizontal = px(EDGE_GLYPH_PX) -
                            (px(HEADER_BAR_PX) - px(QUEUE_TOGGLE_ICON_PX)) / 2,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderSlot {
                    HeaderToggle(AppIcons.Shuffle, active = shuffle) { App.playback.toggleShuffle() }
                }
                Spacer(Modifier.weight(1f))
                HeaderSlot {
                    HeaderToggle(
                        if (repeat == RepeatMode.TRACK) AppIcons.RepeatOne else AppIcons.Repeat,
                        active = repeat != RepeatMode.OFF,
                    ) { App.playback.cycleRepeat() }
                }
            }
        }
    }

    /**
     * 160px of OLED black: back, the two-line title card, then the heart and •••.
     *
     * Hand-built rather than [AppHeader]: that one balances its centred title
     * against an optional search slot this header doesn't have, which would push
     * the text off the screen's midline. Back and ••• are the library header's
     * own 160px squares, so both stay where the thumb expects them.
     */
    @Composable
    private fun PlayerHeader(
        current: Track,
        left: @Composable () -> Unit,
        right: @Composable () -> Unit,
        showTitle: Boolean = true,
        /** A plain page title in the card's place — the queue's own name. */
        title: String? = null,
        /** A second line under [title], at the weight the card's own second line uses. */
        subtitle: String? = null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(px(HEADER_BAR_PX))
                .background(LightThemeTokens.colors.background),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderSlot { left() }
            if (!showTitle && title == null) {
                Spacer(Modifier.weight(1f))
                HeaderSlot { right() }
                return@Row
            }
            // Balances the right-hand square, so the pair centres on the screen.
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (title != null) {
                    // A column even with nothing under it, so a title that
                    // gains a second line doesn't shift up as it arrives.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AppText(
                            title,
                            nSp(20),
                            role = TextRole.Subheading,
                            maxLines = 1,
                            align = TextAlign.Center,
                        )
                        if (subtitle != null) {
                            AppText(
                                subtitle,
                                pxSp(QUEUE_SUBTITLE_PX),
                                lineHeight = pxSp(QUEUE_SUBTITLE_LINE_PX),
                                dim = true,
                                maxLines = 1,
                                align = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    TitleCard(current.title, current.artist)
                }
            }
            HeaderSlot { right() }
        }
    }

    /** One of the header's two 160px squares. */
    @Composable
    private fun HeaderSlot(content: @Composable () -> Unit) {
        Box(
            modifier = Modifier.size(px(HEADER_BAR_PX)),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }

    /**
     * Shuffle and repeat in the queue header, in the big player's sizes.
     *
     * Same rule as the other layouts' toggles: the glyph stays full strength and
     * the underline carries the state, because dimming it made an available
     * control look disabled.
     */
    @Composable
    private fun HeaderToggle(icon: ImageVector, active: Boolean, onClick: () -> Unit) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.size(px(HEADER_BAR_PX)).appClickable(onClick = onClick),
        ) {
            AppIcon(icon, size = px(QUEUE_TOGGLE_ICON_PX))
            Box(
                modifier = Modifier
                    .padding(top = px(UNDERLINE_GAP_PX))
                    .width(px(UNDERLINE_W_PX))
                    .height(px(UNDERLINE_H_PX))
                    .background(
                        if (active) LightThemeTokens.colors.content else Color.Transparent,
                    ),
            )
        }
    }

    /**
     * The cover, or the lyrics in its place — 1080 square, edge to edge.
     *
     * [fullBleed] is Cover Only: the sleeve leaves its panel for the whole area
     * under the header. That area is the screen's width squared, so a square
     * cover fills it exactly — which is also why the header stays put in this
     * mode. Take it away and the box is taller than it is wide, and the sleeve
     * would have to be cropped at the sides to fill it.
     */
    @Composable
    private fun BoxScope.Stage(
        current: Track,
        fullBleed: Boolean,
        onTap: () -> Unit,
        onLongPress: () -> Unit,
    ) {
        val width = LocalConfiguration.current.screenWidthDp.dp
        val widthPx = with(LocalDensity.current) { width.roundToPx() }
        val image = rememberArtwork(current.coverArtId, widthPx)

        // One panel, one occupant. Lyrics take the cover's place rather than
        // sitting over it: a wash dark enough to read against had already hidden
        // most of the sleeve, and the artwork comes straight back on closing.
        val panel = if (fullBleed) {
            Modifier.matchParentSize()
        } else {
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(px(PANEL_H_PX))
                .padding(horizontal = px(INSET_PX))
        }

        if (NowPlayingNav.lyricsOverlay.value) {
            LyricsOverlay(current, Color.Transparent, panel)
            return
        }
        if (image == null) return
        // The cover square inside the panel: cropping a sleeve to the panel's
        // shape would be worse than the margins.
        //
        // Long-pressing it opens the cover full-screen — the same gesture the
        // album page uses for the same thing, and the way back is the same press
        // again rather than hunting for the back arrow.
        Box(
            modifier = panel.slowLongPress(onClick = onTap, onLongPress = onLongPress),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Filling rather than squaring off the height: the two are the
                // same number here, and only this one still holds on a screen
                // whose header doesn't leave a square behind it.
                modifier = if (fullBleed) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxHeight().aspectRatio(1f)
                },
            )
        }
    }


    /**
     * The track, set where the cover would have been.
     *
     * With no artwork the header's small title card is the only thing on a black
     * screen, and it reads as a caption for nothing. Moving it down into the
     * cover's own panel and letting it grow makes the track the page's subject,
     * which is what the cover was doing before.
     */
    @Composable
    private fun BoxScope.TitleBlock(current: Track) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(px(PANEL_H_PX)),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = px(INSET_PX)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Artist above the song, album below it, both in the secondary
                // weight — the credit first, then what you're hearing, then the
                // record it came off.
                HeroLine(current.artist, HERO_SIDE_PX, HERO_SIDE_LINE_PX, dim = true)
                HeroLine(current.title, HERO_TITLE_PX, HERO_TITLE_LINE_PX, dim = false)
                HeroLine(current.album, HERO_SIDE_PX, HERO_SIDE_LINE_PX, dim = true)
            }
        }
    }

    @Composable
    private fun HeroLine(text: String, sizePx: Int, linePx: Int, dim: Boolean) {
        if (text.isBlank()) return
        AppText(
            text,
            pxSp(sizePx),
            lineHeight = pxSp(linePx),
            role = TextRole.Subheading,
            dim = dim,
            maxLines = 1,
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().basicMarquee(),
        )
    }

    /** Seek line and its clocks, both inset [INSET_PX] from either edge. */
    @Composable
    private fun BoxScope.SeekBand(alpha: Float) {
        val position by App.playback.positionMs.collectAsState()
        val duration by App.playback.durationMs.collectAsState()
        var dragRatio by remember { mutableStateOf<Float?>(null) }

        val effectiveDuration = if (duration > 0) duration else 1L
        val ratio = dragRatio ?: (position.toFloat() / effectiveDuration).coerceIn(0f, 1f)

        Band(BAR_UP_PX, BAR_HIT_PX) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = px(INSET_PX))
                    .alpha(alpha)
                    .pointerInput(duration) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            fun ratioAt(x: Float) = (x / size.width).coerceIn(0f, 1f)
                            dragRatio = ratioAt(down.position.x)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                dragRatio = ratioAt(change.position.x)
                                if (!change.pressed) break
                                change.consume()
                            }
                            dragRatio?.let { r -> if (duration > 0) App.playback.seekTo((r * duration).toLong()) }
                            dragRatio = null
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                AppProgressBar(ratio)
            }
        }

        Band(TIME_UP_PX, TIME_ROW_PX) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = px(INSET_PX))
                    .alpha(alpha),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    formatTime(if (dragRatio != null) (ratio * effectiveDuration).toLong() else position),
                    pxSp(TIME_PX),
                    lineHeight = pxSp(TIME_PX),
                )
                Spacer(Modifier.weight(1f))
                AppText(formatTime(duration), pxSp(TIME_PX), lineHeight = pxSp(TIME_PX))
            }
        }
    }

    /** Rewind, play/pause, forward — full white, the only undimmed controls. */
    @Composable
    private fun BoxScope.TransportRow() {
        val isPlaying by App.playback.isPlaying.collectAsState()

        Band(TRANSPORT_UP_PX, TRANSPORT_ROW_PX) {
            Slot(SIDE_X_PX) {
                ScrubButton(AppIcons.FastRewind, direction = -1, size = px(SEEK_BOX_PX)) {
                    App.playback.previous()
                }
            }
            Slot(CENTRE_X_PX, onClick = { App.playback.togglePlayPause() }) {
                AppIcon(
                    if (isPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                    size = px(PLAY_BOX_PX),
                )
            }
            Slot(SCREEN_W_PX - SIDE_X_PX) {
                ScrubButton(AppIcons.FastForward, direction = 1, size = px(SEEK_BOX_PX)) {
                    App.playback.next()
                }
            }
        }
    }

    /**
     * Add, cast and queue: one shared glyph size, all dimmed together.
     *
     * Lyrics used to hold the left slot and now lives in the ••• sheet — it is
     * something you go and read, not something you reach for mid-track.
     */
    @Composable
    private fun BoxScope.SecondaryRow(current: Track, alpha: Float) {
        val size = px(SECONDARY_BOX_PX)
        val libraryTracks by App.library.tracks.collectAsState()
        val liked = libraryTracks.firstOrNull { it.id == current.id }?.liked ?: current.liked
        val source by App.source.collectAsState()
        // Everything behind "+" is a way of filing the track somewhere. On a
        // source with nowhere to file it, the button opens an empty sheet — so
        // it isn't there.
        val canFile = source.supportsLikes || source.supportsPlaylists ||
            source.supportsRatings || source.supportsDownloads

        Band(SECONDARY_UP_PX, SECONDARY_ROW_PX) {
            // "+" rather than a heart: liking is one of several things you might
            // want to file this track under, and the row has one slot for them.
            // Ringed once it *is* liked — the same glyph, so the button doesn't
            // change meaning, with a mark to say the track is already filed.
            if (canFile) {
                Slot(SIDE_X_PX, onClick = { go { AddActionsScreen(it, current.id) } }) {
                    AppIcon(
                        if (liked) AppIcons.AddCircle else AppIcons.Add,
                        size = size,
                        modifier = Modifier.alpha(alpha),
                    )
                }
            }
            Slot(CENTRE_X_PX, onClick = { openOutput() }) {
                AppIcon(AppIcons.Cast, size = size, modifier = Modifier.alpha(alpha))
            }
            Slot(
                SCREEN_W_PX - SIDE_X_PX,
                onClick = { NowPlayingNav.view.value = nextView(NowPlayingNav.view.value) },
            ) {
                // On the queue, the glyph inverts — black lines in a white tile —
                // rather than brightening: it is the only one of these three with
                // a state, and inverting says "you are here" without breaking the
                // row's shared opacity. The alpha wraps the pair, so tile and
                // lines fade together and the white never reads as a lit button.
                val onQueue = NowPlayingNav.view.value == NpView.QUEUE
                Box(
                    modifier = Modifier
                        .alpha(alpha)
                        .size(px(QUEUE_TILE_PX))
                        .then(
                            if (onQueue) {
                                Modifier
                                    .clip(RoundedCornerShape(px(QUEUE_TILE_RADIUS_PX)))
                                    .background(LightThemeTokens.colors.content)
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(
                        AppIcons.FormatListBulleted,
                        size = size,
                        tint = if (onQueue) {
                            LightThemeTokens.colors.background
                        } else {
                            LightThemeTokens.colors.content
                        },
                    )
                }
            }
        }
    }

    /**
     * A full-width band whose *centre* lands [centreUpPx] above the bottom of the
     * screen. Bottom-anchored, so the depths in the design survive any change to
     * what is above them.
     */
    @Composable
    private fun BoxScope.Band(
        centreUpPx: Int,
        heightPx: Int,
        content: @Composable BoxScope.() -> Unit,
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(y = -px(centreUpPx - heightPx / 2))
                .fillMaxWidth()
                .height(px(heightPx)),
            content = content,
        )
    }

    /** A control centred on an exact x within a [Band]. */
    @Composable
    private fun BoxScope.Slot(
        centreX: Int,
        onClick: (() -> Unit)? = null,
        content: @Composable () -> Unit,
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = px(centreX - SLOT_W_PX / 2))
                .width(px(SLOT_W_PX))
                .fillMaxHeight()
                // The click belongs to the slot, not the glyph: hung off the icon
                // it would shrink the target to the drawn size.
                .let { if (onClick != null) it.appClickable(onClick = onClick) else it },
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }

    private fun openOutput() {
        go { OutputScreen(it) }
    }

    private fun openOptions(
        trackId: String,
        artworkMode: ArtworkMode,
        /** Passed in rather than read again: the player has already decided it. */
        coverOnly: Boolean,
    ) {
        go {
            TrackActionsScreen(
                it,
                trackId,
                followCurrent = true,
                showLike = false,
                showDownload = false,
                showRating = false,
                showAddToPlaylist = false,
                // Only worth offering when there is a cover to enlarge; with
                // artwork off there is nothing to show.
                //
                // In Cover Only it stays as the way back out too. The long press
                // on the cover toggles it either way, but that gesture is
                // invisible — the menu is where you look when you don't already
                // know it exists.
                onShowArtwork = if (artworkMode == ArtworkMode.SMALL) {
                    {
                        // Replaces this sheet rather than stacking on it, so back
                        // from the artwork lands on the player as intended.
                        goBack()
                        NowPlayingNav.coverOnly.value = !coverOnly
                        // Going full-screen means going to the picture, so the
                        // controls step out of its way; a tap brings them back.
                        if (!coverOnly) NowPlayingNav.coverChrome.value = false
                    }
                } else {
                    null
                },
                artworkShowing = coverOnly,
                lyricsShowing = NowPlayingNav.lyricsOverlay.value,
                onToggleLyrics = {
                    val show = !NowPlayingNav.lyricsOverlay.value
                    NowPlayingNav.lyricsOverlay.value = show
                    if (show) NowPlayingNav.view.value = NpView.ARTWORK
                },
            )
        }
    }

    private fun openQueueOptions(index: Int, selection: SelectionState) {
        val track = App.playback.queue.value.getOrNull(index) ?: return
        go {
            TrackActionsScreen(
                it,
                track.id,
                queueIndex = index,
                showDownload = false,
                onSelect = { selection.begin(track.id) },
            )
        }
    }
}

// --- Big-artwork variant -----------------------------------------------------

/**
 * Geometry for the full-width-artwork player, in LP3 physical pixels.
 *
 * Every depth is measured up from the bottom of the screen and every x from the
 * left edge, exactly as the design states them, so a number here can be checked
 * against the design with a ruler rather than by adding up the elements above it.
 */
private const val SCREEN_W_PX = 1080
private const val SCREEN_H_PX = 1240

/** Header: the chevron's nudge onto the shared edge axis. */
private const val BACK_BIAS_PX = 15

/** The clocks under the seek line, in physical pixels. */
private const val TIME_PX = 36

/**
 * The block that stands in for the cover when artwork is off.
 *
 * One step up from where it started (72/45): with the whole square to itself and
 * nothing else on the page, the smaller setting read as a caption for a missing
 * picture rather than as the thing the page is about.
 */
private const val HERO_TITLE_PX = 81
private const val HERO_TITLE_LINE_PX = 96
private const val HERO_SIDE_PX = 51
private const val HERO_SIDE_LINE_PX = 63

/** The queue list's bounds: between the header and the shuffle/repeat bar. */
private const val QUEUE_TOP_PX = HEADER_BAR_PX
private const val QUEUE_END_PX = HEADER_BAR_PX

/**
 * The panel the lyrics and a small cover fill: everything on the player between
 * the header and the seek line. The queue page has no controls to clear, so it
 * runs deeper — see [QUEUE_END_PX].
 */
private const val PANEL_END_PX = 420
private const val PANEL_H_PX = SCREEN_H_PX - HEADER_BAR_PX - PANEL_END_PX

/** The underline that marks a header toggle as on. */
private const val UNDERLINE_GAP_PX = 3
private const val UNDERLINE_W_PX = 51
private const val UNDERLINE_H_PX = 3

/** Depths above the bottom of the screen. */
private const val BAR_UP_PX = 360
private const val TIME_UP_PX = 320
private const val TRANSPORT_UP_PX = 240
private const val SECONDARY_UP_PX = 120

/**
 * Band heights, sized so the touchable ones tile without overlapping: the seek
 * line owns 320–400, the transport 170–310 and the secondary row 70–170.
 */
private const val BAR_HIT_PX = 80
private const val TIME_ROW_PX = 44
private const val TRANSPORT_ROW_PX = 140
private const val SECONDARY_ROW_PX = 100

/** Horizontal placement: outer controls 280px in, clocks and seek line 80px in. */
private const val SIDE_X_PX = 280
private const val CENTRE_X_PX = SCREEN_W_PX / 2
private const val INSET_PX = 80
private const val SLOT_W_PX = 200

/**
 * Icon boxes, in physical pixels and every one a multiple of 3 — so each is a
 * whole number of dp as well. A fractional dp puts the glyph's edges between
 * pixels and its strokes go soft, which is exactly what you see on a stem one
 * pixel wide.
 *
 * The ink is smaller than its box: play and pause fill 14/24 of it, the double
 * arrows 12/24, the bottom row's glyphs 18/24. The comments give what a ruler
 * finds on screen.
 */
private const val PLAY_BOX_PX = 138      // 80px of ink
private const val SEEK_BOX_PX = 114      // 57px
private const val SECONDARY_BOX_PX = 54  // 40px
private const val HEADER_ICON_PX = 66

/** The queue header's second line — the card's own second line, which it sits beside. */
private const val QUEUE_SUBTITLE_PX = 36
private const val QUEUE_SUBTITLE_LINE_PX = 42

/**
 * Shuffle and repeat at the foot of the queue, sized off the Light music app's
 * own pair rather than off this header's other glyphs — they are the page's only
 * controls, and at header size they read as an afterthought.
 */
private const val QUEUE_TOGGLE_ICON_PX = 90
private const val BACK_ICON_PX = 57

/** The tile the queue glyph inverts into while that page is on screen. */
private const val QUEUE_TILE_PX = 66
private const val QUEUE_TILE_RADIUS_PX = 6

/** Transport is full white; everything else on black is knocked back. */
private const val DIM_ALPHA = 0.45f

/**
 * The same controls once they are standing on a sleeve rather than on black.
 *
 * Well up from the 0.45 these controls carry on black: over a picture they have
 * to hold their own against whatever is behind them, and the wash only does half
 * that job. Judged on the device across several values — the transport stays the
 * only thing at full strength, so the hierarchy survives the higher setting.
 */
private const val ON_COVER_ALPHA = 0.75f

/**
 * The wash under the controls in Cover Only, and how solid it gets at the very
 * bottom of it.
 *
 * 520px clears the topmost control — the seek line's band starts 400px up — so
 * the gradient has run out before it reaches anything, rather than stopping
 * against an edge you can see.
 */
private const val SCRIM_H_PX = 520
private const val SCRIM_ALPHA = 0.82f

