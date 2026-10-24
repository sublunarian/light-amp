package com.sublunar.amp.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.sublunar.amp.App
import com.sublunar.amp.art.ArtworkNeed
import com.sublunar.amp.ui.PlayerTheme
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.TitleCard
import com.sublunar.amp.ui.components.rememberArtwork
import com.sublunar.amp.ui.currentScale
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * An album's cover at full width, from its own page: whole rather than cropped,
 * hanging from the header, with only which record this is and the way back.
 *
 * Reads the album from the library rather than taking a copy, so a sync that
 * corrects a title changes it here too.
 */
class AlbumArtworkScreen(
    sealed: SealedLightActivity,
    private val albumId: String,
) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val albums by App.library.albums.collectAsState()
        val album = remember(albums, albumId) { albums.firstOrNull { it.id == albumId } }
        val image = rememberArtwork(album?.coverArtId, currentScale().windowWidthPx, ArtworkNeed.FOCUSED)

        PlayerTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                AppHeader(
                    onBack = { goBack() },
                    titleContent = {
                        TitleCard(album?.title.orEmpty(), album?.artist.orEmpty())
                    },
                )
                // Clipped here, on an untransformed box, rather than on the
                // image's own layer: a layer's clip applies before its transform,
                // so a zoomed picture would grow over the header, clip and all.
                Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                    if (image != null) {
                        // Pinch to look closer, drag to move around inside it. The
                        // player's cover has none of this: it shares the screen
                        // with a seek line you drag.
                        var zoom by remember { mutableStateOf(1f) }
                        var pan by remember { mutableStateOf(Offset.Zero) }
                        var frame by remember { mutableStateOf(IntSize.Zero) }

                        // Keeps the picture over every pixel it covered at rest.
                        // Scaling anchors the top-left, so the content runs from
                        // `pan` to `pan + size * zoom`, which spans the frame
                        // exactly while pan is between `-(size * (zoom - 1))` and
                        // zero. At zoom 1 the picture sits back where it started.
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
                                    // Top-left, so the clamp above is stated in
                                    // the picture's own coordinates.
                                    transformOrigin = TransformOrigin(0f, 0f)
                                }
                                .pointerInput(Unit) {
                                    detectTransformGestures { centroid, drag, pinch, _ ->
                                        val was = zoom
                                        val now = (was * pinch).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                        // Whatever is under the fingers stays
                                        // under them.
                                        val focus = (centroid - pan) * (now / was)
                                        // The drag is scaled by the zoom, so a
                                        // swipe crosses the same fraction of the
                                        // sleeve however far in you are.
                                        zoom = now
                                        pan = clamped(centroid - focus + drag * now, now)
                                    }
                                },
                        )
                    } else {
                        AppIcon(
                            AppIcons.Album,
                            size = px(163),
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
 * How far a sleeve may be pinched. The bitmap is decoded at screen width and the
 * file is often smaller still, so past four there are only bigger pixels. Under
 * one, the picture is already as wide as the screen.
 */
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 4f
