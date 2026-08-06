@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.sublunar.amp.ui.components

import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
    val state = produceState(initialValue = cached, coverArtId, sizePx, hidden) {
        if (cached != null) return@produceState
        value = if (hidden || coverArtId.isNullOrBlank()) null else App.artwork.load(coverArtId, sizePx)
    }
    return state.value
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

@Composable
fun Modifier.rowClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val (tick, thud) = rememberTapHaptics()
    return this.combinedClickable(
        interactionSource = interaction,
        indication = null,
        onClick = { tick(); onClick() },
        onLongClick = onLongClick?.let { handler -> { thud(); handler() } },
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
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .rowClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 0.6f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(1f.gridUnitsAsDp()))
        }
        Column(modifier = Modifier.weight(1f)) {
            LightText(text = title, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
