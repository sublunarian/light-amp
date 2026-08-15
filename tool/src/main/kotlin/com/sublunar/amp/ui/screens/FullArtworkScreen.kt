package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.sublunar.amp.App
import com.sublunar.amp.ui.PlayerTheme
import androidx.compose.foundation.layout.fillMaxWidth
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.TitleCard
import com.sublunar.amp.ui.components.rememberArtwork
import com.sublunar.amp.ui.n
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * An album's cover at full width, from its own page.
 *
 * Whole rather than cropped to a square, hanging from the header's bottom edge,
 * with nothing playing behind it: no seek line, no play button, just which
 * record this is and the way back. Tapping a sleeve on the album page is a
 * request to look at it, not to start it.
 *
 * Reads the album from the library rather than taking a copy, so a sync that
 * corrects a title changes it here too.
 */
class AlbumArtworkScreen(
    sealed: SealedLightActivity,
    private val albumId: String,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val albums by App.library.albums.collectAsState()
        val album = remember(albums, albumId) { albums.firstOrNull { it.id == albumId } }
        val width = LocalConfiguration.current.screenWidthDp.dp
        val widthPx = with(LocalDensity.current) { width.roundToPx() }
        val image = rememberArtwork(album?.coverArtId, widthPx)

        PlayerTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                AppHeader(
                    onBack = { goBack() },
                    titleContent = {
                        TitleCard(album?.title.orEmpty(), album?.artist.orEmpty())
                    },
                )
                // Clipped here, on the box, rather than on the image's own layer.
                // A layer's `clip` applies to its contents *before* the layer's
                // transform, so scaling the picture scales its clip rectangle
                // with it — the picture grows over the header and takes
                // permission to be there along with it. This box is never
                // transformed, so what it clips to is the space below the
                // header, whatever the zoom does inside it.
                Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                    if (image != null) {
                        // Pinch to look closer, drag to move around inside it —
                        // the reason to open a sleeve on its own page is often a
                        // detail in the corner of it. The player's cover has
                        // none of this: there the picture shares the screen with
                        // a seek line you drag, and two gestures over one image
                        // would fight.
                        var zoom by remember { mutableStateOf(1f) }
                        var pan by remember { mutableStateOf(Offset.Zero) }
                        var frame by remember { mutableStateOf(IntSize.Zero) }

                        /**
                         * Keeps the picture over every pixel it covered at rest.
                         *
                         * Scaling anchors the top-left, so the content runs from
                         * `pan` to `pan + size * zoom`. Holding pan between
                         * `-(size * (zoom - 1))` and zero is exactly the range
                         * where that still spans the frame — push past either end
                         * and the background shows through at an edge, which on a
                         * sleeve reads as the image having come apart. At zoom 1
                         * the range collapses to zero and the picture sits back
                         * where it started.
                         */
                        fun clamped(next: Offset, at: Float): Offset {
                            val slackX = (frame.width * (at - 1f)).coerceAtLeast(0f)
                            val slackY = (frame.height * (at - 1f)).coerceAtLeast(0f)
                            return Offset(
                                next.x.coerceIn(-slackX, 0f),
                                next.y.coerceIn(-slackY, 0f),
                            )
                        }

                        Image(
                            bitmap = image,
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .onSizeChanged { frame = it }
                                .graphicsLayer {
                                    scaleX = zoom
                                    scaleY = zoom
                                    translationX = pan.x
                                    translationY = pan.y
                                    // Top-left, so the clamp above can be stated
                                    // in the picture's own coordinates rather
                                    // than around a moving centre.
                                    transformOrigin = TransformOrigin(0f, 0f)
                                }
                                .pointerInput(Unit) {
                                    detectTransformGestures { centroid, drag, pinch, _ ->
                                        val was = zoom
                                        val now = (was * pinch).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                        // Whatever is under the fingers stays
                                        // under them: without this the picture
                                        // slides away from the detail you were
                                        // pinching towards.
                                        val focus = (centroid - pan) * (now / was)
                                        // The drag is scaled by the zoom, so a
                                        // swipe crosses the same *fraction of the
                                        // sleeve* however far in you are. Moved
                                        // one-to-one with the finger it takes
                                        // eight swipes at 8x to cross what one
                                        // swipe crosses at 1x, which is what
                                        // makes a close look feel stuck.
                                        zoom = now
                                        pan = clamped(centroid - focus + drag * now, now)
                                    }
                                },
                        )
                    } else {
                        AppIcon(
                            AppIcons.Album,
                            size = n(64),
                            tint = LightThemeTokens.colors.contentSecondary,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        }
    }
}

/**
 * How far a sleeve may be pinched.
 *
 * Four is about where it stops being worth it: the bitmap is decoded at screen
 * width, and the file behind it is often smaller still, so past this there are
 * no more pixels to find — only bigger ones. Under one there is nothing to see
 * either, the picture already being as wide as the screen.
 */
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 4f

