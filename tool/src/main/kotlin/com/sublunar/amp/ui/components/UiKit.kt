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
import com.sublunar.amp.App
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.ui.lightClickable
import com.sublunar.amp.art.ArtworkNeed
import kotlinx.coroutines.delay

// --- Artwork ----------------------------------------------------------------

/** Waits between tries for the cover in front of the user — see [rememberArtwork]. */
private val FOCUSED_RETRY_MS = longArrayOf(3_000L, 8_000L, 20_000L)

@Composable
fun rememberArtwork(
    coverArtId: String?,
    sizePx: Int,
    need: ArtworkNeed = ArtworkNeed.BROWSING,
): ImageBitmap? {
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
    // Whether this cover may be fetched right now is part of the question, so
    // it is part of the key: an empty answer used to be final until the cover
    // itself changed, which left the playing sleeve blank for the whole song
    // after one missed fetch, and a list of placeholders blank after Wi-Fi had
    // arrived. Read unconditionally — see ARCHITECTURE.md on conditional reads.
    val rules by App.rules.collectAsState()
    val allowed = when (need) {
        ArtworkNeed.FOCUSED -> rules.mayFetchFocusedArt
        ArtworkNeed.BROWSING -> rules.mayMoveHeavyBytes
    }
    LaunchedEffect(coverArtId, sizePx, hidden, allowed) {
        if (image != null) return@LaunchedEffect
        if (hidden || coverArtId.isNullOrBlank()) return@LaunchedEffect
        image = App.artwork.load(coverArtId, sizePx, need)
        // The cover in front of the user is worth asking for again: on poor
        // reception the first request loses to the song it belongs to. A few
        // tries, further apart each time, and only while it may be fetched. A
        // row in a list is not — there are hundreds, and the next scroll asks.
        if (need == ArtworkNeed.FOCUSED && allowed) {
            for (wait in FOCUSED_RETRY_MS) {
                if (image != null) break
                delay(wait)
                image = App.artwork.load(coverArtId, sizePx, need)
            }
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

/**
 * A menu row, drawn the way the phone's own menus are.
 *
 * Measured off LightOS's stock tools — the Phone list, Calendar's settings, the
 * SDK-built Weather's: one line for a row that does something, and for a row
 * that names a setting, the name small above its current value large, the
 * value being the information. The stock menus set the line in Heading; tried,
 * and it shouted on a page of rows, so it sits one step down Light's scale at
 * Copy — the size the phone's own lists title in. Text starts [MENU_INSET_PX] in, the two units
 * Light's text-only lists use; the lists with a mark and a cover keep the 72px
 * axis Light's own Music list has ([ROW_LEAD_PX]).
 */
@Composable
fun TextRow(
    title: String,
    modifier: Modifier = Modifier,
    /**
     * The setting's current value, drawn large under the title — which becomes
     * its label. For a row that names a setting rather than doing something.
     */
    value: String? = null,
    /**
     * A line under the title saying what the row does, or how it stands.
     *
     * One line by default: most are a name or a count, and letting those wrap
     * would make a list of rows ragged to no purpose. Raised where it is a
     * sentence explaining what the row does, which is a different job.
     */
    subtitle: String? = null,
    subtitleLines: Int = 1,
    onLongClick: (() -> Unit)? = null,
    /** Sits before the text, for a row that needs saying what it opens. */
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /**
     * Null for a row that states something rather than opening it — a setting
     * with only one possible value, which More still shows so the page's
     * modifiers all read alike. It doesn't take a press, and its main line is
     * dimmed to say so before the press is tried.
     */
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let {
                if (onClick == null) it else it.slowLongPress(onClick, onLongPress = onLongClick)
            }
            .padding(horizontal = px(MENU_INSET_PX), vertical = px(MENU_PAD_PX)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(px(MENU_GAP_PX)))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (value != null) {
                MenuLabel(title)
                MenuLine(value, dim = onClick == null)
            } else {
                MenuLine(title, dim = onClick == null)
                if (!subtitle.isNullOrBlank()) MenuLabel(subtitle, maxLines = subtitleLines)
            }
        }
        if (trailing != null) trailing()
    }
}

/** A menu row's main line: Light's Copy — Heading read too big, see [TextRow]. */
@Composable
private fun MenuLine(text: String, dim: Boolean) {
    AppText(
        text,
        pxSp(LightType.COPY_PX),
        lineHeight = pxSp(LightType.COPY_LINE_PX),
        role = TextRole.Copy,
        dim = dim,
        maxLines = 1,
    )
}

/** A menu row's small line, above a value or under a verb: Light's Detail. */
@Composable
private fun MenuLabel(text: String, maxLines: Int = 1) {
    AppText(
        text,
        pxSp(LightType.DETAIL_PX),
        lineHeight = pxSp(LightType.DETAIL_LINE_PX),
        role = TextRole.Detail,
        dim = true,
        maxLines = maxLines,
    )
}

/**
 * Where a menu row's text starts: two grid units, as on the phone's own
 * text-only lists (the Phone tool, every stock Settings page). The same 80px
 * the corner glyphs and the bar glyphs sit on.
 */
const val MENU_INSET_PX = 80

/** Above and below a row's text: with Copy's line, a 136px pitch. */
private const val MENU_PAD_PX = 32

/** Between a leading glyph and the text: one unit. */
private const val MENU_GAP_PX = 40

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AppText(
            text,
            pxSp(LightType.COPY_PX),
            lineHeight = pxSp(LightType.COPY_LINE_PX),
            dim = true,
            align = TextAlign.Center,
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    AppText(
        text,
        pxSp(LightType.DETAIL_PX),
        lineHeight = pxSp(LightType.DETAIL_LINE_PX),
        role = TextRole.Detail,
        dim = true,
        modifier = Modifier.padding(
            start = px(MENU_INSET_PX),
            top = px(SECTION_TOP_PX),
            bottom = px(SECTION_BOTTOM_PX),
        ),
    )
}

private const val SECTION_TOP_PX = 40
private const val SECTION_BOTTOM_PX = 12
