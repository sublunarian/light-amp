@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.sublunar.amp.ui.components

import androidx.compose.foundation.combinedClickable
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sublunar.amp.App
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** Shared layout constants (tuned further on-device). */
object Dimens {
    val ArtworkCorner: Dp = 3.dp
}

// --- Artwork ----------------------------------------------------------------

@Composable
fun rememberArtwork(coverArtId: String?, sizePx: Int): ImageBitmap? {
    // Gated here rather than at each drawing site: this is the only way into the
    // artwork cache, so switching covers off also stops fetching and decoding
    // them, which is most of what they cost.
    val hidden by App.hideArtwork.collectAsState()
    // Seeded from the memory cache so a cover that has already been decoded is
    // there on the first frame rather than after one — see ArtworkLoader.peek.
    val cached = if (hidden) null else App.artwork.peek(coverArtId, sizePx)
    // Re-created for each cover rather than held across the change, which is
    // what left the previous track's sleeve on screen when you skipped: with
    // produceState, `initialValue` applies only to the first composition and the
    // held value survives a key change until the producer assigns a new one — so
    // a cover already in the memory cache, which returns early rather than
    // loading, never assigned anything and the old bitmap simply stayed.
    var image by remember(coverArtId, sizePx, hidden) { mutableStateOf(cached) }
    LaunchedEffect(coverArtId, sizePx, hidden) {
        if (image != null) return@LaunchedEffect
        image = if (hidden || coverArtId.isNullOrBlank()) {
            null
        } else {
            App.artwork.load(coverArtId, sizePx)
        }
    }
    return image
}

// --- Clickable helpers ------------------------------------------------------

/**
 * Haptics for presses: a light tick for taps, the heavier pattern for holds.
 *
 * The LP3 has no press animation — [lightClickable] deliberately draws no
 * indication — so touch feedback is the only confirmation a tap registered.
 */
@Composable
fun rememberTapHaptics(): Pair<() -> Unit, () -> Unit> {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) {
        Pair(
            { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
            { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
        )
    }
}

/**
 * A long press that has to be held for longer than the platform default.
 *
 * Compose's ~500ms is short enough that scrolling a list with a lazy thumb
 * opens a context menu instead, and on the player it means resting a finger on
 * the cover throws you into the full-size page. Both are gestures that should
 * feel deliberate.
 */
@Composable
fun Modifier.slowLongPress(
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    holdMs: Long = LONG_PRESS_MS,
): Modifier {
    val (tick, thud) = rememberTapHaptics()
    if (onLongPress == null) return this.rowClickable(onClick = onClick)
    // Held rather than keyed on, so the gesture isn't torn down and restarted
    // every time a row recomposes — which, now that every list row comes
    // through here, would happen mid-press often enough to matter.
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongPress)
    return this.pointerInput(holdMs) {
        awaitEachGesture {
            awaitFirstDown()
            val startedAt = System.currentTimeMillis()
            val up = withTimeoutOrNull(holdMs) { waitForUpOrCancellation() }
            when {
                up != null -> {
                    tick()
                    click()
                }
                // Null means either the hold outlasted the deadline or the press
                // was taken away — a scroll claiming the pointer, most often —
                // and only the clock tells those apart. Reading both as a long
                // press is what let a flick down a list open a context menu.
                System.currentTimeMillis() - startedAt >= holdMs -> {
                    thud()
                    long()
                    // Swallow the release so it doesn't also read as a tap.
                    waitForUpOrCancellation()
                }
            }
        }
    }
}

/** Long enough to be deliberate, short enough not to feel broken. */
const val LONG_PRESS_MS = 900L

/**
 * A row that responds to a tap, and optionally to a long press.
 *
 * Any row that wants a long press goes through [slowLongPress] for it, so every
 * long press in the app is the same length. `combinedClickable` can't be told
 * one: it reads the timeout from the ambient ViewConfiguration, which is
 * Android's own ~400ms, and the composition local that would override it is a
 * blocked import in the SDK sandbox. So rows with a menu behind them held for
 * half as long as the artwork did, which is a gesture meaning two things.
 */
@Composable
fun Modifier.rowClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier {
    if (onLongClick != null) {
        return this.slowLongPress(onClick = onClick, onLongPress = onLongClick)
    }
    val interaction = remember { MutableInteractionSource() }
    val (tick, _) = rememberTapHaptics()
    return this.combinedClickable(
        interactionSource = interaction,
        indication = null,
        onClick = { tick(); onClick() },
    )
}

/**
 * Icon/button counterpart to [rowClickable]: the SDK's clickable plus a haptic
 * tick. Used everywhere instead of calling `lightClickable` directly, so every
 * press in the app feels the same.
 */
@Composable
fun Modifier.appClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val (tick, _) = rememberTapHaptics()
    return this.lightClickable(enabled = enabled) {
        tick()
        onClick()
    }
}

// --- Rows -------------------------------------------------------------------

/** A single-line text row (no artwork) — used for menus and text lists. */
@Composable
fun TextRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onLongClick: (() -> Unit)? = null,
    /** Sits before the text, for a row that needs saying what it opens. */
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /**
     * Null for a row that states something rather than opening it — a setting
     * with only one possible value, which More still shows so the page's
     * modifiers all read alike. It doesn't take a press, and its title is
     * lightened to say so before the press is tried.
     */
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let {
                if (onClick == null) it else it.slowLongPress(onClick, onLongPress = onLongClick)
            }
            .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 0.6f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(1f.gridUnitsAsDp()))
        }
        Column(modifier = Modifier.weight(1f)) {
            LightText(
                text = title,
                variant = LightTextVariant.Copy,
                lighten = onClick == null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                LightText(text = subtitle, variant = LightTextVariant.Detail, lighten = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LightText(text = text, variant = LightTextVariant.Copy, lighten = true, align = TextAlign.Center)
    }
}

@Composable
fun SectionLabel(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier.padding(start = 1.5f.gridUnitsAsDp(), top = 1f.gridUnitsAsDp(), bottom = 0.3f.gridUnitsAsDp()),
    )
}
