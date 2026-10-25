package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import com.sublunar.amp.App
import com.sublunar.amp.data.LyricLine
import com.sublunar.amp.data.LyricsRepository
import com.sublunar.amp.data.Track
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.CenteredNote
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp

/** Where the active synced-lyric line sits in the pane, as a fraction of its height. */
private const val LYRIC_ANCHOR = 0.38f

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

private fun indexForTime(lines: List<LyricLine>, positionMs: Long): Int {
    var result = -1
    for (i in lines.indices) {
        val t = lines[i].timeMs ?: break
        if (t > positionMs) break
        result = i
    }
    return result
}
