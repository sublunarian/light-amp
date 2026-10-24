package com.sublunar.amp.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import com.sublunar.amp.App
import com.sublunar.amp.art.ArtworkNeed
import com.sublunar.amp.data.ArtworkMode
import com.sublunar.amp.data.Connectivity
import com.sublunar.amp.data.LastSection
import com.sublunar.amp.data.LyricLine
import com.sublunar.amp.data.LyricsRepository
import com.sublunar.amp.data.PhoneFiles
import com.sublunar.amp.data.RepeatMode
import com.sublunar.amp.data.Track
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.PlayerTheme
import com.sublunar.amp.ui.components.AppArtwork
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.AppProgressBar
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.AutoScroll
import com.sublunar.amp.ui.components.DropIndicatorLine
import com.sublunar.amp.ui.components.HEADER_BAR_PX
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.LIST_EDGE_PX
import com.sublunar.amp.ui.components.ListScrollBar
import com.sublunar.amp.ui.components.ROW_ART_PX
import com.sublunar.amp.ui.components.ROW_GAP_PX
import com.sublunar.amp.ui.components.ROW_LEAD_PX
import com.sublunar.amp.ui.components.ROW_SUB_LINE_PX
import com.sublunar.amp.ui.components.ROW_SUB_PX
import com.sublunar.amp.ui.components.ROW_TITLE_LINE_PX
import com.sublunar.amp.ui.components.ROW_TITLE_PX
import com.sublunar.amp.ui.components.SCROLLBAR_LANE_PX
import com.sublunar.amp.ui.components.SelectionArtwork
import com.sublunar.amp.ui.components.SelectionHeader
import com.sublunar.amp.ui.components.SelectionState
import com.sublunar.amp.ui.components.TextRole
import com.sublunar.amp.ui.components.TitleCard
import com.sublunar.amp.ui.components.appClickable
import com.sublunar.amp.ui.components.dragReorderContainer
import com.sublunar.amp.ui.components.dragRowTarget
import com.sublunar.amp.ui.components.dropInsertIndex
import com.sublunar.amp.ui.components.formatTime
import com.sublunar.amp.ui.components.rememberArtwork
import com.sublunar.amp.ui.components.rememberDragReorderState
import com.sublunar.amp.ui.components.rememberSelection
import com.sublunar.amp.ui.components.rowClickable
import com.sublunar.amp.ui.components.slowLongPress
import com.sublunar.amp.ui.currentScale
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Where the active synced-lyric line sits in the pane, as a fraction of its height. */
private const val LYRIC_ANCHOR = 0.38f

/**
 * Shared width for the trailing control on a queue row, so the drag handles line
 * up down the right edge of the list.
 */
private const val QUEUE_TRAILING_SLOT_PX = 51

/** Where every other list's covers start: its edge and the download mark's slot. */
private const val QUEUE_START_PX = LIST_EDGE_PX + ROW_LEAD_PX

private const val QUEUE_ROW_PX = 160

/** A queue row's key. The same track can be queued twice, so its place is part of it. */
private fun queueKey(index: Int, track: Track) = "$index-${track.id}"

/**
 * What Now Playing shows. Held outside the screen so it outlasts navigating away
 * and back. The screen resets [showQueue] and [coverChrome] on each open.
 */
private object NowPlayingNav {
    /** The queue pane rather than the player. */
    val showQueue = mutableStateOf(false)
    val lyricsOverlay = mutableStateOf(false)

    /**
     * Cover Only: the cover behind the controls.
     *
     * Not a screen of its own: the player draws itself exactly as always and only
     * what is behind it changes, so there is no second copy of the header, seek
     * line and transport to keep in step.
     */
    val coverOnly = mutableStateOf(false)

    /**
     * Whether the controls are up while the cover fills the screen.
     *
     * Not kept across opens, unlike [coverOnly]: hiding them is for a moment's
     * look at a sleeve, and a player that opens with no controls reads as a fault.
     */
    val coverChrome = mutableStateOf(true)

    /** Going full-screen means going to the picture, so the controls step out of its way. */
    fun setCoverOnly(on: Boolean) {
        coverOnly.value = on
        if (on) coverChrome.value = false
    }
}

class NowPlayingScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    /**
     * The player is one of the bar's three destinations, so it records itself
     * while up and hands the section back on the way out, since only this screen
     * knows when it stops showing — see [LastSection].
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
        // A fresh open lands on the controls, not the queue: the pane outlasts a
        // trip into track options, but not leaving the player. Cover Only is kept,
        // as a preference about how the player looks. The controls start out of
        // the way however you got here, and a tap brings them back.
        NowPlayingNav.showQueue.value = false
        NowPlayingNav.coverChrome.value = false
    }

    @Composable
    override fun Content() {
        val track by App.playback.currentTrack.collectAsState()
        val queue by App.playback.queue.collectAsState()
        val index by App.playback.index.collectAsState()
        val showQueue = NowPlayingNav.showQueue.value

        PlayerTheme {
            val current = track
            if (current == null) {
                NothingPlaying()
                return@PlayerTheme
            }
            if (showQueue) QueuePlayer(queue, index) else ArtPlayer(current)
        }
    }

    /** Reached only by opening the player before anything has been played. */
    @Composable
    private fun NothingPlaying() {
        Column(modifier = Modifier.fillMaxSize()) {
            // Same header as the player, so the way out doesn't move.
            AppHeader(
                onBack = { goBack() },
                title = "Now Playing",
            )
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppText("Nothing playing", pxSp(LightType.COPY_PX))
            }
        }
    }

    @Composable
    private fun LyricsOverlay(current: Track, modifier: Modifier) {
        val karaoke by App.settings.karaokeLyrics.state.collectAsState()
        val position by App.playback.positionMs.collectAsState()
        var outcome by remember(current.id) { mutableStateOf<LyricsRepository.Outcome?>(null) }
        var loading by remember(current.id) { mutableStateOf(true) }
        // Looking lyrics up is two network calls (the server, then lrclib.net)
        // for something nobody asked to download, so Wi-Fi Only doesn't. Words
        // downloaded with the song are read either way: the decision is passed
        // down, so the free copy is never withheld along with the paid one.
        // The rule is App.metadataAllowed's. Collecting what it depends on
        // re-reads it when the link or the mode changes.
        val dataMode by App.settings.dataMode.state.collectAsState()
        val unmetered by Connectivity.unmetered.collectAsState(initial = false)
        LaunchedEffect(current.id, dataMode, unmetered) {
            loading = true
            outcome = LyricsRepository.forTrack(
                current,
                App.source.value.id,
                App.serverClient.value,
                allowNetwork = App.metadataAllowed(),
            )
            loading = false
        }
        val lyrics = (outcome as? LyricsRepository.Outcome.Found)?.lyrics
        val lines = lyrics?.lines ?: emptyList()
        val synced = (lyrics?.synced == true) && karaoke
        val activeIndex = if (synced) indexForTime(lines, position) else -1

        Box(modifier = modifier.fillMaxWidth()) {
            when {
                loading -> CenteredText("Loading lyrics…")
                outcome == LyricsRepository.Outcome.NotFetched ->
                    CenteredText("Lyrics wait for Wi-Fi")
                outcome == LyricsRepository.Outcome.Unavailable ->
                    CenteredText("Couldn't reach the lyrics service")
                lines.isEmpty() -> CenteredText("No lyrics")
                else -> {
                    val listState = rememberLazyListState()
                    // Hold the active line at LYRIC_ANCHOR rather than the top.
                    LaunchedEffect(activeIndex, synced) {
                        if (!synced || activeIndex < 0) return@LaunchedEffect
                        val viewport = listState.layoutInfo.viewportSize.height
                        if (viewport <= 0) return@LaunchedEffect
                        listState.animateScrollToItem(
                            activeIndex,
                            -(viewport * LYRIC_ANCHOR).toInt(),
                        )
                    }
                    // Room under the last line for it to reach the anchor: the
                    // pane's own height less the anchor's share of it.
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val tailPadding = maxHeight * (1f - LYRIC_ANCHOR)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = px(51),
                                end = px(51),
                                top = px(46),
                                bottom = if (synced) tailPadding else px(46),
                            ),
                        ) {
                            itemsIndexed(lines) { i, line ->
                                AppText(
                                    text = line.text,
                                    size = pxSp(LightType.DETAIL_PX),
                                    lineHeight = pxSp(54),
                                    align = TextAlign.Center,
                                    dim = synced && i != activeIndex,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = px(5)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CenteredText(text: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AppText(text, pxSp(LightType.DETAIL_PX), dim = true)
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
        listState: LazyListState = rememberLazyListState(),
    ) {
        val drag = rememberDragReorderState<String>()
        val artworkHidden by App.hideArtwork.collectAsState()
        val rowPx = with(LocalDensity.current) { px(QUEUE_ROW_PX).toPx() }
        // Read unconditionally, branch on the result — see ARCHITECTURE.md on
        // conditional composable calls.
        val rules by App.rules.collectAsState()
        val onPhone by App.library.downloadedTrackIds.collectAsState()
        val canStream = rules.mayStream
        // Only the upcoming part of the queue, past the current track, can be dragged into.
        val minIndex = index + 1
        val orderedKeys = remember(queue) { queue.mapIndexed(::queueKey) }
        val indexOfKey = remember(orderedKeys) { orderedKeys.withIndex().associate { (i, key) -> key to i } }
        // takeIf on the condition that gates dragging at all: the track can end
        // while a drag is held, and once the playing row is the last there is no
        // upcoming range left. dragRowTarget would then clamp into an empty range
        // and throw, before the pointerInput restart that ends the drag has had a
        // composition to take effect.
        val dropTarget: DropTarget? = drag.draggingIndex?.takeIf { queue.size > minIndex }?.let { from ->
            val target = dragRowTarget(queue.size, from, drag.dragOffsetY, rowPx, minIndex)
            val movingIndices = drag.draggingKeys.mapNotNull { indexOfKey[it] }.toSet()
            remember(queue, from, drag.draggingKeys, target) {
                val insertAt = dropInsertIndex(queue.size, from, movingIndices, target)
                val remaining = orderedKeys.filterIndexed { i, _ -> i !in movingIndices }
                DropTarget(remaining.getOrNull(insertAt))
            }
        }

        // The playing track stays at the top. Opening the page jumps there
        // outright, while a track ending animates, so the list is seen to move.
        var anchored by remember { mutableStateOf(false) }
        LaunchedEffect(index) {
            val target = index.coerceAtLeast(0)
            if (anchored) listState.animateScrollToItem(target) else listState.scrollToItem(target)
            anchored = true
        }

        drag.AutoScroll(listState, rowPx)

        Box(
            modifier
                .dragReorderContainer(
                    state = drag,
                    enabled = queue.size > minIndex,
                    restartKey = queue to index,
                    orderedKeys = orderedKeys,
                    rowPx = rowPx,
                    minIndex = minIndex,
                    groupOf = { hitKey ->
                        val hitId = indexOfKey[hitKey]?.let { queue.getOrNull(it) }?.id
                        if (hitId != null && hitId in selection.selected && selection.count > 1) {
                            orderedKeys.filterIndexed { i, _ -> i >= minIndex && queue[i].id in selection.selected }
                                .toSet()
                        } else {
                            setOf(hitKey)
                        }
                    },
                    onDrop = { movingIndices, insertAt -> App.playback.moveGroupInQueue(movingIndices, insertAt) },
                ),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(start = px(QUEUE_START_PX), end = px(SCROLLBAR_LANE_PX)),
            ) {
                itemsIndexed(queue, key = ::queueKey) { i, track ->
                    val isCurrent = i == index
                    val isPast = i < index
                    // A row that has to wait for the server stays in the queue,
                    // stepped back like a played row. It plays again the moment
                    // the rules allow — see LinkRules.
                    val mustWait = !canStream && track.id !in onPhone && !PhoneFiles.isPhoneFile(track.id)
                    val rowKey = queueKey(i, track)
                    val isDragging = rowKey in drag.draggingKeys
                    // Clear coords on dispose: LazyColumn recycles nodes, so a stale
                    // entry would silently report the next occupant's position.
                    DisposableEffect(rowKey) {
                        onDispose { drag.clear(rowKey) }
                    }
                    Box(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(px(QUEUE_ROW_PX))
                                .onGloballyPositioned { drag.rowCoords[rowKey] = it }
                                // Real row goes invisible; DragOverlay draws the floating stand-in.
                                .alpha(if (isDragging) 0f else if (isPast || mustWait) 0.5f else 1f)
                                .rowClickable(
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
                            QueueRowContent(track, isCurrent, selection, artworkHidden)
                            // Drag handle for reordering within the upcoming part of the queue.
                            if (!isPast && !isCurrent) {
                                AppIcon(
                                    AppIcons.Dehaze,
                                    size = px(51),
                                    modifier = Modifier
                                        .width(px(QUEUE_TRAILING_SLOT_PX))
                                        .onGloballyPositioned { drag.iconCoords[rowKey] = it },
                                )
                            }
                        }
                        if (dropTarget?.beforeKey == rowKey) DropIndicatorLine(Modifier.align(Alignment.TopStart))
                    }
                }
                if (dropTarget != null && dropTarget.beforeKey == null) {
                    item { DropIndicatorLine() }
                }
            }
            if (drag.draggingIndex != null) {
                // Found by key rather than by walking the queue, in queue order.
                val dragged = drag.draggingKeys.mapNotNull { indexOfKey[it] }.sorted()
                DragOverlay(queue, dragged, drag.dragStartTops, drag.fingerOffsetY, artworkHidden, selection)
            }
        }
    }

    private data class DropTarget(val beforeKey: String?)

    /** A queue row's cover and lines, shared by the list and the floating copy being dragged. */
    @Composable
    private fun RowScope.QueueRowContent(
        track: Track,
        isCurrent: Boolean,
        selection: SelectionState,
        artworkHidden: Boolean,
    ) {
        if (selection.active) {
            SelectionArtwork(track.coverArtId, track.id in selection.selected, size = px(ROW_ART_PX))
            Spacer(Modifier.width(px(ROW_GAP_PX)))
        } else if (!artworkHidden) {
            AppArtwork(track.coverArtId, size = px(ROW_ART_PX))
            Spacer(Modifier.width(px(ROW_GAP_PX)))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    AppIcon(AppIcons.Waveform, size = px(46))
                    Spacer(Modifier.width(px(15)))
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
    }

    /**
     * Floating copy of the row(s) being dragged, drawn outside the LazyColumn so it
     * survives the real row being recycled by auto-scroll. [fingerOffsetY] excludes
     * auto-scroll's own contribution, since the overlay's position shouldn't move twice.
     */
    @Composable
    private fun BoxScope.DragOverlay(
        queue: List<Track>,
        /** The dragged rows' places in [queue], in queue order. */
        dragged: List<Int>,
        dragStartTops: Map<String, Float>,
        fingerOffsetY: Float,
        artworkHidden: Boolean,
        selection: SelectionState,
    ) {
        dragged.forEach { i ->
            val track = queue[i]
            val top = dragStartTops[queueKey(i, track)] ?: return@forEach
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(px(QUEUE_ROW_PX))
                    .align(Alignment.TopStart)
                    .graphicsLayer { translationY = top + fingerOffsetY }
                    .padding(start = px(QUEUE_START_PX), end = px(SCROLLBAR_LANE_PX)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QueueRowContent(track, isCurrent = false, selection, artworkHidden)
                Spacer(Modifier.width(px(QUEUE_TRAILING_SLOT_PX)))
            }
        }
    }

    @Composable
    private fun ScrubButton(icon: ImageVector, direction: Int, onSkip: () -> Unit) {
        val scope = rememberCoroutineScope()
        Box(
            // Fills the cell so tap-to-skip and hold-to-scrub get the whole square.
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    var scrubbing = false
                    val hold = scope.launch {
                        delay(SCRUB_HOLD_MS)
                        scrubbing = true
                        while (isActive) {
                            val pos = App.playback.positionMs.value
                            val dur = App.playback.durationMs.value
                            if (dur > 0) App.playback.seekTo((pos + direction * SCRUB_STEP_MS).coerceIn(0L, dur))
                            delay(SCRUB_REPEAT_MS)
                        }
                    }
                    waitForUpOrCancellation()
                    hold.cancel()
                    if (!scrubbing) onSkip()
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(icon, size = px(SEEK_BOX_PX))
        }
    }

    /**
     * The player: a header over the cover, with the controls below it.
     *
     * Below the header nothing is stacked: each control is anchored to the bottom
     * of the screen at the depth the design gives it (see [Band]), so a font-metric
     * change above can't move them.
     *
     * Cover Only is this same page with the sleeve moved out of its panel to fill
     * everything under the header. The controls dim, gain a wash to be read
     * against, and a tap can put them away.
     */
    @Composable
    private fun ArtPlayer(current: Track) {
        val artwork by App.settings.artwork.state.collectAsState()
        // Parked (see PlaybackController.waitingFor) or buffering: the credit
        // line says so, or the screen looks like a paused player and gets
        // tapped again.
        val waiting by App.playback.waitingFor.collectAsState()
        val buffering by App.playback.buffering.collectAsState()
        val credit = waiting ?: if (buffering) "Buffering…" else current.artist
        // With no cover the track takes the panel. Lyrics want the whole panel,
        // so then the title stays in the header.
        val heroTitle = artwork == ArtworkMode.NONE && !NowPlayingNav.lyricsOverlay.value
        // Either of these leaves nothing to put behind the controls: no cover at
        // all, or the lyrics in its place.
        val coverOnly = NowPlayingNav.coverOnly.value &&
            artwork != ArtworkMode.NONE &&
            !NowPlayingNav.lyricsOverlay.value
        val chrome = NowPlayingNav.coverChrome.value

        Column(modifier = Modifier.fillMaxSize()) {
            // The same header as every other page, with the track card in the
            // title's place. Kept on black explicitly: the stage below may be a
            // full-bleed cover.
            Box(modifier = Modifier.background(LightThemeTokens.colors.background)) {
                AppHeader(
                    onBack = { goBack() },
                    // Nothing in the card's place in hero mode, where the
                    // title lives on the stage instead — see heroTitle.
                    titleContent = if (heroTitle) null else ({ TitleCard(current.title, credit) }),
                    rightAction = HeaderAction(
                        AppIcons.MoreVert,
                        onLongClick = { go { SettingsScreen(it) } },
                    ) { openOptions(current.id, artwork, coverOnly) },
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                Stage(
                    current,
                    fullBleed = coverOnly,
                    // A tap puts the controls away and brings them back. The long
                    // press that opened the cover closes it. A plain tap toggling
                    // the cover itself was too easy on a phone meant to be light.
                    onTap = { if (coverOnly) NowPlayingNav.coverChrome.value = !chrome },
                    onLongPress = { NowPlayingNav.setCoverOnly(!coverOnly) },
                )
                if (heroTitle) TitleBlock(current, credit)
                if (!coverOnly || chrome) {
                    // On the cover everything steps back a little, but by less
                    // than the secondary row on black: over a picture it has to
                    // stay legible — see ON_COVER_ALPHA.
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
     * The wash the controls sit on once the cover is behind them: there to stop
     * white-on-white, not to dim the picture, so it has faded out a little above
     * the seek line.
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
     * The queue: a header naming the page (while selecting, the count and the
     * selection controls), the list, and shuffle and repeat in a bar at the foot.
     */
    @Composable
    private fun QueuePlayer(queue: List<Track>, index: Int) {
        val shuffle by App.playback.shuffle.collectAsState()
        val repeat by App.playback.repeatMode.collectAsState()
        val selection = rememberSelection("queue")

        Box(modifier = Modifier.fillMaxSize()) {
            if (selection.active) {
                SelectionHeader(
                    selection = selection,
                    onDelete = {
                        App.playback.removeFromQueue(selection.selected)
                        // Stay in selection mode with nothing chosen: pruning a
                        // queue is usually more than one pass.
                        selection.begin()
                    },
                    onConfirm = {
                        openSelectionActions(
                            selection.pick(queue) { it.id },
                            selection,
                            // Everything here is in the queue already, and
                            // downloads are a library job.
                            showAddToQueue = false,
                            showDownload = false,
                        )
                    },
                )
            } else {
                AppHeader(
                    onBack = { NowPlayingNav.showQueue.value = false },
                    titleContent = {
                        QueueTitle(
                            // The page, not the track: that one is the row marked
                            // as playing.
                            title = "Queue",
                            // Only with something current: "0 of 12" would be a
                            // position no row is at.
                            subtitle = if (index in queue.indices) {
                                "${index + 1} of ${queue.size}"
                            } else {
                                null
                            },
                        )
                    },
                )
            }
            val listState = rememberLazyListState()
            Box(
                // Whatever the screen leaves between the header and the bar.
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = px(QUEUE_TOP_PX), bottom = px(QUEUE_END_PX)),
            ) {
                QueueView(
                    queue,
                    index,
                    selection,
                    Modifier.fillMaxSize(),
                    listState = listState,
                )
                // Outside the list's margins, against the screen edge, so it stays
                // clear of the drag handles.
                ListScrollBar(listState)
            }
            // Shuffle and repeat as a bar of their own: the queue is where you set
            // how it will be played.
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
                QueueToggle(AppIcons.Shuffle, active = shuffle) { App.playback.toggleShuffle() }
                Spacer(Modifier.weight(1f))
                QueueToggle(
                    if (repeat == RepeatMode.TRACK) AppIcons.RepeatOne else AppIcons.Repeat,
                    active = repeat != RepeatMode.OFF,
                ) { App.playback.cycleRepeat() }
            }
        }
    }

    /**
     * The queue's page title: its name, and where in it you are. A column even with
     * nothing under it, so the title doesn't shift as a second line arrives.
     */
    @Composable
    private fun QueueTitle(title: String, subtitle: String?) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppText(
                title,
                pxSp(LightType.FINE_PX),
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
    }

    /**
     * Shuffle or repeat at the foot of the queue, in a square the bar's height. The
     * glyph stays full strength and the underline carries the state: dimming it
     * made an available control look disabled.
     */
    @Composable
    private fun QueueToggle(icon: ImageVector, active: Boolean, onClick: () -> Unit) {
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
     * The cover, or the lyrics in its place.
     *
     * [fullBleed] is Cover Only: the sleeve leaves its panel for the whole area
     * under the header. On the LP3 that area is square, so the cover fills it
     * exactly, which is also why the header stays put in this mode.
     */
    @Composable
    private fun BoxScope.Stage(
        current: Track,
        fullBleed: Boolean,
        onTap: () -> Unit,
        onLongPress: () -> Unit,
    ) {
        // Decoded at the panel's own width, which a full-bleed cover fills, and
        // fetched wherever the music itself is — see ArtworkNeed.
        val image = rememberArtwork(current.coverArtId, currentScale().windowWidthPx, ArtworkNeed.FOCUSED)

        // Lyrics take the cover's place rather than sitting over it: a wash dark
        // enough to read against hides most of the sleeve anyway.
        val panel = if (fullBleed) {
            Modifier.matchParentSize()
        } else {
            // Everything above the controls rather than a height of its own, so
            // the seek line stays at its depth on any panel.
            Modifier
                .align(Alignment.TopStart)
                .fillMaxSize()
                .padding(bottom = px(PANEL_END_PX))
                .padding(horizontal = px(INSET_PX))
        }

        if (NowPlayingNav.lyricsOverlay.value) {
            LyricsOverlay(current, panel)
            return
        }
        if (image == null) return
        // The cover square inside the panel: cropping a sleeve to the panel's
        // shape would be worse than the margins. Long-pressing it opens the cover
        // full-screen, as on the album page, and the same press comes back.
        Box(
            modifier = panel.slowLongPress(onClick = onTap, onLongPress = onLongPress),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Filling rather than squaring off the height: the same on the
                // LP3, and only this one holds where the area isn't square.
                modifier = if (fullBleed) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxHeight().aspectRatio(1f)
                },
            )
        }
    }

    /**
     * The track, set where the cover would have been: with no artwork the header's
     * small title card reads as a caption for nothing.
     */
    @Composable
    private fun BoxScope.TitleBlock(current: Track, credit: String) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxSize()
                .padding(bottom = px(PANEL_END_PX)),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = px(INSET_PX)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Artist above the song, album below it.
                HeroLine(credit, HERO_SIDE_PX, HERO_SIDE_LINE_PX, dim = true)
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

    /** Rewind, play/pause, forward: full white, the only undimmed controls. */
    @Composable
    private fun BoxScope.TransportRow() {
        val isPlaying by App.playback.isPlaying.collectAsState()
        Band(TRANSPORT_UP_PX, TRANSPORT_ROW_PX) {
            Slot(SlotAt.FromStart(SIDE_X_PX)) {
                ScrubButton(AppIcons.FastRewind, direction = -1) {
                    App.playback.previous()
                }
            }
            Slot(SlotAt.Centre, onClick = { App.playback.togglePlayPause() }) {
                AppIcon(
                    if (isPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                    size = px(PLAY_BOX_PX),
                )
            }
            Slot(SlotAt.FromEnd(SIDE_X_PX)) {
                ScrubButton(AppIcons.FastForward, direction = 1) {
                    App.playback.next()
                }
            }
        }
    }

    /** Add, cast and queue: one shared glyph size, all dimmed together. */
    @Composable
    private fun BoxScope.SecondaryRow(current: Track, alpha: Float) {
        val size = px(SECONDARY_BOX_PX)
        val libraryTracks by App.library.tracks.collectAsState()
        val liked = remember(libraryTracks, current) {
            libraryTracks.firstOrNull { it.id == current.id }?.liked ?: current.liked
        }

        Band(SECONDARY_UP_PX, SECONDARY_ROW_PX) {
            // "+" rather than a heart: liking is one of several ways to file the
            // track. Ringed once it is liked, so the button keeps its meaning.
            Slot(SlotAt.FromStart(SIDE_X_PX), onClick = { go { AddActionsScreen(it, current.id) } }) {
                AppIcon(
                    if (liked) AppIcons.AddCircle else AppIcons.Add,
                    size = size,
                    modifier = Modifier.alpha(alpha),
                )
            }
            Slot(SlotAt.Centre, onClick = { openOutput() }) {
                AppIcon(AppIcons.Cast, size = size, modifier = Modifier.alpha(alpha))
            }
            Slot(
                SlotAt.FromEnd(SIDE_X_PX),
                onClick = { NowPlayingNav.showQueue.value = !NowPlayingNav.showQueue.value },
            ) {
                // On the queue the glyph inverts into a white tile rather than
                // brightening, which would break the row's shared opacity. The
                // alpha wraps the pair, so tile and lines fade together.
                val onQueue = NowPlayingNav.showQueue.value
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

    /** Where a [Slot] sits across its band: a distance in from either edge, or the middle. */
    private sealed interface SlotAt {
        data class FromStart(val px: Int) : SlotAt
        data class FromEnd(val px: Int) : SlotAt
        data object Centre : SlotAt
    }

    /**
     * A control centred within a [Band] at [at].
     *
     * Anchored to an edge or to the centre rather than put at an x across the
     * screen, so the right-hand control mirrors the left on any width without
     * the screen's own width appearing anywhere in the arithmetic.
     */
    @Composable
    private fun BoxScope.Slot(
        at: SlotAt,
        onClick: (() -> Unit)? = null,
        content: @Composable () -> Unit,
    ) {
        val place = when (at) {
            is SlotAt.FromStart ->
                Modifier.align(Alignment.CenterStart).offset(x = px(at.px - SLOT_W_PX / 2))
            is SlotAt.FromEnd ->
                Modifier.align(Alignment.CenterEnd).offset(x = -px(at.px - SLOT_W_PX / 2))
            SlotAt.Centre -> Modifier.align(Alignment.Center)
        }
        Box(
            modifier = place
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
                // Only with a cover to enlarge. In Cover Only it is the way back
                // out too: the hold on the cover is invisible, the menu isn't.
                onShowArtwork = if (artworkMode == ArtworkMode.SMALL) {
                    {
                        goBack()
                        NowPlayingNav.setCoverOnly(!coverOnly)
                    }
                } else {
                    null
                },
                artworkShowing = coverOnly,
                lyricsShowing = NowPlayingNav.lyricsOverlay.value,
                onToggleLyrics = {
                    val show = !NowPlayingNav.lyricsOverlay.value
                    NowPlayingNav.lyricsOverlay.value = show
                    if (show) NowPlayingNav.showQueue.value = false
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

// Geometry, in canvas pixels. Every depth is measured up from the bottom of the
// screen and every x in from the nearer edge, as the design states them, so a
// number can be checked against the design with a ruler. Nothing states the
// screen's own size: another panel gets the same controls at the same depths
// and a cover sized to the rest.

/** The clocks under the seek line. */
private const val TIME_PX = 36

/** The block that stands in for the cover when artwork is off. */
private const val HERO_TITLE_PX = LightType.HEADING_PX
private const val HERO_TITLE_LINE_PX = LightType.HEADING_LINE_PX
private const val HERO_SIDE_PX = LightType.FINE_PX
private const val HERO_SIDE_LINE_PX = LightType.FINE_LINE_PX

/**
 * What the controls take at the foot of the player; the panel the lyrics and a
 * small cover fill is everything above it. Clears the seek line's band, which
 * starts 400px up, by a little.
 */
private const val PANEL_END_PX = 420

/** The queue list's bounds: between the header and the shuffle/repeat bar. */
private const val QUEUE_TOP_PX = HEADER_BAR_PX
private const val QUEUE_END_PX = HEADER_BAR_PX

/** The underline that marks a queue toggle as on. */
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
private const val INSET_PX = 80
private const val SLOT_W_PX = 200

/**
 * Icon boxes, every one a multiple of 3px so each is a whole number of dp too:
 * a fractional dp puts the glyph's edges between pixels and its strokes go soft.
 * The ink is smaller than its box (play and pause fill 14/24 of it, the double
 * arrows 12/24, the bottom row's glyphs 18/24). The comments give what a ruler
 * finds on screen.
 */
private const val PLAY_BOX_PX = 138      // 80px of ink
private const val SEEK_BOX_PX = 114      // 57px
private const val SECONDARY_BOX_PX = 54

/** The queue header's second line, the size of the title card's own. */
private const val QUEUE_SUBTITLE_PX = 36
private const val QUEUE_SUBTITLE_LINE_PX = 42

/**
 * Shuffle and repeat at the foot of the queue, sized off the Light music app's
 * own pair: they are the page's only controls, and at header size they read as
 * an afterthought.
 */
private const val QUEUE_TOGGLE_ICON_PX = 90

/** The tile the queue glyph inverts into while that page is on screen. */
private const val QUEUE_TILE_PX = 66
private const val QUEUE_TILE_RADIUS_PX = 6

/** Transport is full white; everything else on black is knocked back. */
private const val DIM_ALPHA = 0.45f

/**
 * The same controls standing on a sleeve rather than on black: over a picture
 * they have to hold their own, and the wash only does half that job. The
 * transport stays the only thing at full strength.
 */
private const val ON_COVER_ALPHA = 0.75f

/**
 * The wash under the controls in Cover Only, and how solid it gets at the very
 * bottom. 520px clears the seek line's band (400px up), so the gradient has run
 * out before it reaches anything.
 */
private const val SCRIM_H_PX = 520
private const val SCRIM_ALPHA = 0.82f

/** Scrubbing: how long a hold takes to start it, how often it steps, and how far. */
private const val SCRUB_HOLD_MS = 300L
private const val SCRUB_REPEAT_MS = 250L
private const val SCRUB_STEP_MS = 5_000L
