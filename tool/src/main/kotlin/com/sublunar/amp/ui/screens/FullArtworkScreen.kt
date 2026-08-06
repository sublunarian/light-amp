package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.TitleCard
import com.sublunar.amp.ui.components.rememberArtwork
import com.sublunar.amp.ui.n
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * The cover at full width, under the player's own header.
 *
 * The image is shown whole rather than filled to a square: a sleeve that isn't
 * square is usually a scan or a single's artwork, and cropping it is exactly what
 * this page exists to undo. It hangs from the header's bottom edge, so whatever
 * shape it is, its top is where the header ends.
 *
 * The header carries what's playing, live, so a track change follows through
 * here too. Leaving is the back button only: a tap-to-dismiss on a picture you
 * opened deliberately goes off at the first stray touch.
 */
class FullArtworkScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val track by App.playback.currentTrack.collectAsState()
        val isPlaying by App.playback.isPlaying.collectAsState()
        val width = LocalConfiguration.current.screenWidthDp.dp
        val widthPx = with(LocalDensity.current) { width.roundToPx() }
        val image = rememberArtwork(track?.coverArtId, widthPx)

        PlayerTheme {
            Box(modifier = Modifier.fillMaxSize()) {
                // Under the cover, not over it: the bar keeps its place on the
                // seam, but the half of it that would cross the picture is hidden
                // behind it. Touches still reach it — neither the header's middle
                // nor the image takes pointer input.
                SeekLine()
                Column(modifier = Modifier.fillMaxSize()) {
                    AppHeader(
                        onBack = { goBack() },
                        titleContent = { TitleCard(track?.title.orEmpty(), track?.artist.orEmpty()) },
                        rightAction = HeaderAction(
                            if (isPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                        ) { App.playback.togglePlayPause() },
                    )
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (image != null) {
                            Image(
                                bitmap = image,
                                contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                alignment = Alignment.TopCenter,
                                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
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

    @Composable
    private fun BoxScope.SeekLine() {
        val position by App.playback.positionMs.collectAsState()
        val duration by App.playback.durationMs.collectAsState()
        var dragRatio by remember { mutableStateOf<Float?>(null) }

        val effective = if (duration > 0) duration else 1L
        val ratio = dragRatio ?: (position.toFloat() / effective).coerceIn(0f, 1f)

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                // Centred on the seam between the header and the artwork: the
                // cover is drawn over the bar, so only the played portion shows,
                // as a line along the top edge of the picture.
                .offset(y = px(HEADER_BAR_PX - BAR_HIT_PX / 2))
                .fillMaxWidth()
                .height(px(BAR_HIT_PX))
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
            // Only the part already played. The hairline that marks the rest of
            // the track is dropped here: this page is the cover, and a rule
            // across the top of it is furniture the picture doesn't need.
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio)
                    .height(px(BAR_H_PX))
                    .background(LightThemeTokens.colors.content),
            )
        }
    }
}

/** Matches the header the bar is centred on. */
private const val HEADER_BAR_PX = 160
private const val BAR_HIT_PX = 80

/** Matches LightProgressBar's played bar: half a grid unit. */
/**
 * An album's cover at full width, from its own page.
 *
 * The same picture, shown the same way as [FullArtworkScreen] — whole rather
 * than cropped to a square, hanging from the header's bottom edge — but with
 * nothing playing behind it: no seek line, no play button, just which record
 * this is and the way back. Tapping a sleeve on the album page is a request to
 * look at it, not to start it.
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
                        // detail in the corner of it. The player's version of
                        // this page has none of it: there the picture shares the
                        // screen with a seek line you drag, and two gestures over
                        // one image would fight.
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

private const val BAR_H_PX = 30

