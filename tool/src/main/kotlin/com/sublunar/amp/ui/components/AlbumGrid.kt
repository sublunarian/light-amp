package com.sublunar.amp.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.sublunar.amp.App
import com.sublunar.amp.data.Album
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * Albums as a wall of sleeves: three across, tightly spaced, no words.
 *
 * A record is its cover — for anyone who knows their own library, a grid of
 * artwork is faster to read than a column of titles, and it fits four times as
 * many on a screen. Titles aren't dropped so much as deferred: the album's own
 * page carries all of them, and this is the way in.
 *
 * The A–Z strip works here exactly as it does on the list, because both are
 * driven by item indices — see [ScrollTarget].
 */
@Composable
fun AlbumGrid(
    albums: List<Album>,
    onOpen: (Album) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    /**
     * Room down the right edge for scroll furniture.
     *
     * Zero by default: the grid has none, which is the point — the covers get
     * the whole width instead.
     */
    endInset: Dp = px(LIST_EDGE_PX),
    onLongPress: ((Album) -> Unit)? = null,
    header: (LazyGridScope.() -> Unit)? = null,
) {
    // Long enough to be worth one. A discography or a shelf of favourites is a
    // couple of flicks from top to bottom, where a bar is furniture you have to
    // look past rather than something you would ever reach for — and the covers
    // give up width for it either way. Counted in albums rather than in what the
    // layout reports visible, so the answer is the same on the first frame as on
    // the second and the grid doesn't reflow under you.
    val longEnough = albums.size >= GRID_BAR_MIN_ITEMS
    // Always on, for any list long enough to be worth dragging.
    val furniture = longEnough
    // Read unconditionally: behind an `if`, this stops subscribing to the state
    // it reads and the bar freezes where it was — the same trap the songs tab
    // documents for collectAsState.
    val target = rememberScrollTarget(state)
    Box(modifier = modifier.fillMaxSize()) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        state = state,
        modifier = Modifier.fillMaxSize(),
        // Only the right edge gives way. The covers stay hard against the left,
        // and the lane is taken out of the width they share — so turning the bar
        // on costs a little sleeve rather than the alignment of the whole grid.
        // The lane is narrower than a list's: a list insets its text far enough
        // to keep a long title clear of the letters, and a grid has no such
        // problem — the covers can come right up to the bar.
        contentPadding = PaddingValues(
            start = px(LIST_EDGE_PX),
            end = if (furniture) px(GRID_SCROLLBAR_LANE_PX) else endInset,
            top = px(LIST_TOP_PX),
            bottom = px(LIST_TOP_PX),
        ),
        horizontalArrangement = Arrangement.spacedBy(px(GRID_GAP_PX)),
        verticalArrangement = Arrangement.spacedBy(px(GRID_GAP_PX)),
    ) {
        header?.invoke(this)
        items(albums.size, key = { albums[it].id }) { index ->
            val album = albums[index]
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .rowClickable(
                        onClick = { onOpen(album) },
                        onLongClick = onLongPress?.let { { it(album) } },
                    ),
            ) {
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) { GridCover(album) }
            }
        }
    }
        if (furniture) ListScrollBar(target, lane = px(GRID_SCROLLBAR_LANE_PX))
    }
}

/**
 * One sleeve, filling its cell.
 *
 * Not [AppArtwork]: that one takes a fixed size, which is the wrong shape of
 * answer here — the grid decides how wide a cell is, and the cover follows.
 */
@Composable
private fun GridCover(album: Album) {
    val density = LocalDensity.current
    val image = rememberArtwork(album.coverArtId, with(density) { px(GRID_CELL_PX).roundToPx() })
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(px(GRID_CORNER_PX)))
            .background(LightThemeTokens.colors.contentSecondary.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            // A sleeveless record still needs to be a target, and a title would
            // be the only thing in a grid that has none.
            AppText(
                album.title,
                pxSp(GRID_FALLBACK_PX),
                lineHeight = pxSp(GRID_FALLBACK_LINE_PX),
                align = TextAlign.Center,
                maxLines = 3,
                modifier = Modifier.padding(px(GRID_GAP_PX)),
            )
        }
    }
}

/**
 * The lane the grid's scroll bar lives in, and the room the covers give up for
 * it. Only wide enough to hold the bar and stay grabbable — the point of a grid
 * is the covers, so the bar sits hard against the edge with the artwork tight
 * against it rather than floating in a list's wider gutter.
 */
private const val GRID_SCROLLBAR_LANE_PX = 30

/**
 * Albums below which the grid goes without a bar — about three screens of
 * covers at three across, which is the point where scrolling stops being one
 * gesture and starts being a journey.
 */
private const val GRID_BAR_MIN_ITEMS = 30

/**
 * Three across, with the gaps tight enough that the covers read as a wall.
 *
 * Not configurable. Two-across and titles-under-covers were both options once;
 * three bare covers is what the screen is for, and a setting for every taste is
 * its own kind of clutter on a phone that exists to have less of it.
 */
private const val GRID_COLUMNS = 3
private const val GRID_GAP_PX = 12

/**
 * Nominal cell size, used only to pick the decode resolution — the cells
 * themselves are sized by the grid. Close to the real width on the LP3: the
 * content is 1080 less the edges and the strip, split three ways.
 */
private const val GRID_CELL_PX = 300
private const val GRID_CORNER_PX = 6
private const val GRID_FALLBACK_PX = 39
private const val GRID_FALLBACK_LINE_PX = 48
