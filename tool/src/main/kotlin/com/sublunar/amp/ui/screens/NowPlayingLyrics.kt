package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import com.sublunar.amp.App
import com.sublunar.amp.data.LyricLine
import com.sublunar.amp.data.LyricsRepository
import com.sublunar.amp.data.Track
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.CenteredNote
import com.sublunar.amp.ui.components.TextRole
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp

@Composable
internal fun LyricsOverlay(current: Track, modifier: Modifier) {
    val karaoke by App.settings.karaokeLyrics.state.collectAsState()
    // Read only inside the derivedStateOf below: a position tick then
    // recomposes nothing unless the active line changes.
    val position = App.playback.positionMs.collectAsState()
    var outcome by remember(current.id) { mutableStateOf<LyricsRepository.Outcome?>(null) }
    var loading by remember(current.id) { mutableStateOf(true) }
    // Looking lyrics up is two network calls (the server, then lrclib.net)
    // for something nobody asked to download, so Wi-Fi Only doesn't. Words
    // downloaded with the song are read either way: the decision is passed
    // down, so the free copy is never withheld along with the paid one.
    // The rule is App.metadataAllowed's. Keyed on it, on the server answering
    // and on a Wi-Fi/cellular hop, so a lookup that reached nothing is tried
    // again once it might; a restart with the answer cached costs nothing.
    val rules by App.rules.collectAsState()
    LaunchedEffect(current.id, rules.mayTalkToServer, rules.serverReachable, rules.unmetered) {
        loading = true
        outcome = LyricsRepository.forTrack(
            current,
            App.source.value.id,
            App.serverClient.value,
            allowNetwork = App.metadataAllowed(),
            dao = App.browsedDao(),
        )
        loading = false
    }
    val lyrics = (outcome as? LyricsRepository.Outcome.Found)?.lyrics
    val lines = lyrics?.lines ?: emptyList()
    val synced = (lyrics?.synced == true) && karaoke
    val activeIndex by remember(lines, synced) {
        derivedStateOf { if (synced) indexForTime(lines, position.value) else -1 }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        when {
            loading -> CenteredNote("Loading lyrics…")
            outcome == LyricsRepository.Outcome.NotFetched ->
                CenteredNote("Lyrics wait for Wi-Fi")
            outcome == LyricsRepository.Outcome.Unavailable ->
                CenteredNote("Couldn't reach the lyrics service")
            lines.isEmpty() -> CenteredNote("No lyrics")
            else -> {
                val listState = rememberLazyListState()
                val oneLinePx = with(LocalDensity.current) { px(LYRIC_LINE_PX + 2 * LYRIC_GAP_PX).roundToPx() }
                // The active line centred in the pane, on its measured height: a
                // lyric that wraps is taller than one line. One not yet laid out
                // is aimed at as a single line, then set right without animating.
                LaunchedEffect(activeIndex, synced) {
                    if (!synced || activeIndex < 0) return@LaunchedEffect
                    val info = listState.layoutInfo
                    val viewport = info.viewportSize.height
                    if (viewport <= 0) return@LaunchedEffect
                    val aimed = info.visibleItemsInfo.firstOrNull { it.index == activeIndex }?.size ?: oneLinePx
                    // scrollToItem's offset counts from below the top padding.
                    listState.animateScrollToItem(activeIndex, info.beforeContentPadding - (viewport - aimed) / 2)
                    val after = listState.layoutInfo
                    val item = after.visibleItemsInfo.firstOrNull { it.index == activeIndex } ?: return@LaunchedEffect
                    val paneMiddle = (after.viewportStartOffset + after.viewportEndOffset) / 2
                    val off = item.offset + item.size / 2 - paneMiddle
                    if (off != 0) listState.scrollBy(off.toFloat())
                }
                // Synced, half the pane above the first line and below the last,
                // so every line can reach the middle; read through, none.
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val halfPane = maxHeight / 2
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = px(51),
                            end = px(51),
                            top = if (synced) halfPane else px(0),
                            bottom = if (synced) halfPane else px(46),
                        ),
                    ) {
                        itemsIndexed(lines) { i, line ->
                            AppText(
                                text = line.text,
                                size = pxSp(LYRIC_PX),
                                lineHeight = pxSp(LYRIC_LINE_PX),
                                role = TextRole.Fine,
                                exact = true,
                                align = TextAlign.Center,
                                dim = synced && i != activeIndex,
                                modifier = Modifier.fillMaxWidth().padding(vertical = px(LYRIC_GAP_PX)),
                            )
                        }
                    }
                }
            }
        }
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

/** A little under the Notes tool's body size (Light's fine, 52), on its natural line. */
private const val LYRIC_PX = 48
private const val LYRIC_LINE_PX = 58

/** Above and below each lyric, so the next one starts further off than a wrapped line. */
private const val LYRIC_GAP_PX = 5
