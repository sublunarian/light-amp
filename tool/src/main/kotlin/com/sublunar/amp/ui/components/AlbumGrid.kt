package com.sublunar.amp.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import com.sublunar.amp.data.Album
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * Albums as a wall of sleeves: three across, tightly spaced, no words. A grid of
 * artwork is faster to read than a column of titles for anyone who knows their own
 * library, and the album's page carries the titles.
 */
@Composable
fun AlbumGrid(
    albums: List<Album>,
    onOpen: (Album) -> Unit,
    onLongPress: (Album) -> Unit,
    state: LazyGridState,
    /** Full-width rows drawn before the album at each index: a grouped page's headings. */
    headings: Map<Int, @Composable () -> Unit> = emptyMap(),
    /** Full-width rows after the last sleeve. */
    footer: LazyGridScope.() -> Unit = {},
    header: LazyGridScope.() -> Unit,
) {
    // Read unconditionally: behind an `if` it stops following the state and the
    // bar freezes.
    val target = rememberScrollTarget(state)
    val rows = rememberLatest(albums)
    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            state = state,
            modifier = Modifier.fillMaxSize(),
            // The bar draws over the covers, so no lane is kept for it.
            contentPadding = PaddingValues(horizontal = px(LIST_EDGE_PX)),
            horizontalArrangement = Arrangement.spacedBy(px(GRID_GAP_PX)),
            verticalArrangement = Arrangement.spacedBy(px(GRID_GAP_PX)),
        ) {
            header()
            // Runs of covers between the headings, each heading spanning the row.
            val bounds = (headings.keys + 0 + albums.size).filter { it in 0..albums.size }.toSortedSet().toList()
            bounds.zipWithNext().forEach { (from, until) ->
                headings[from]?.let { heading ->
                    item(key = "heading-$from", span = { GridItemSpan(maxLineSpan) }) { heading() }
                }
                // Read through [rows], never captured (see LatestList).
                items(until - from, key = { rows[from + it]?.id ?: (from + it) }) { offset ->
                    val album = rows[from + offset] ?: return@items
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .rowClickable(
                                onClick = { onOpen(album) },
                                onLongClick = { onLongPress(album) },
                            ),
                    ) {
                        GridCover(album)
                    }
                }
            }
            footer()
        }
        OverlayScrollBar(target, scrolling = state.isScrollInProgress)
    }
}

/**
 * One sleeve, filling its cell. Not [AppArtwork], which takes a fixed size: here the
 * grid sizes the cell and the cover follows.
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
            // A sleeveless record still needs something to tap.
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

/** Three across, gaps tight enough that the covers read as a wall. Deliberately not a setting. */
private const val GRID_COLUMNS = 3
private const val GRID_GAP_PX = 12

/** Nominal cell size, only to pick the decode resolution: near the real width on the LP3. */
private const val GRID_CELL_PX = 300
private const val GRID_CORNER_PX = 6
private const val GRID_FALLBACK_PX = 39
private const val GRID_FALLBACK_LINE_PX = 48
