@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.sublunar.amp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.sublunar.amp.App
import com.sublunar.amp.art.ArtworkNeed
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Waits between tries for the cover in front of the user, see [rememberArtwork]. */
private val FOCUSED_RETRY_MS = longArrayOf(3_000L, 8_000L, 20_000L)

@Composable
fun rememberArtwork(
    coverArtId: String?,
    sizePx: Int,
    need: ArtworkNeed = ArtworkNeed.BROWSING,
): ImageBitmap? {
    // Gated here, the only way into the artwork cache, so hiding covers also stops
    // fetching and decoding them.
    val hidden by App.hideArtwork.collectAsState()
    // Seeded from the memory cache so a decoded cover is there on the first frame.
    val cached = if (hidden) null else App.artwork.peek(coverArtId, sizePx)
    // State keyed on the cover, not held across the change: produceState kept its
    // value across a key change until the producer assigned one, so a cover served
    // from the memory cache left the previous track's sleeve on screen.
    var image by remember(coverArtId, sizePx, hidden) { mutableStateOf(cached) }
    // Whether the cover may be fetched now is part of the effect's key, so a cover
    // refused once is asked for again when the rules allow it. Read unconditionally
    // (ARCHITECTURE.md on conditional reads).
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
        // reception the first request loses to its song. A list row isn't, the
        // next scroll asks.
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

/**
 * Haptics for presses: a light tick for taps, the heavier pattern for holds. The LP3
 * draws no press indication, so this is the only confirmation a tap registered.
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
 * A long press held for [LONG_PRESS_MS], longer than the platform's ~500ms, which
 * let a lazy thumb scrolling a list open a context menu.
 */
@Composable
fun Modifier.slowLongPress(onClick: () -> Unit, onLongPress: () -> Unit): Modifier {
    val (tick, thud) = rememberTapHaptics()
    // Read through state rather than keyed on, so a recomposing row doesn't
    // restart the gesture mid-press.
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongPress)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown()
            val startedAt = System.currentTimeMillis()
            val up = withTimeoutOrNull(LONG_PRESS_MS) { waitForUpOrCancellation() }
            when {
                up != null -> {
                    tick()
                    click()
                }
                // Null means the hold outlasted the deadline or a scroll took the
                // pointer away. Only the clock tells them apart.
                System.currentTimeMillis() - startedAt >= LONG_PRESS_MS -> {
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
 * A row that responds to a tap, and optionally to a long press through
 * [slowLongPress], so every long press in the app is the same length.
 * `combinedClickable` can't be given a timeout: it reads Android's ~400ms from the
 * ambient ViewConfiguration, and the local that overrides it is blocked in the SDK
 * sandbox.
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
 * A finger pressed and slid along the element, as a fraction of its width:
 * [onMove] on the press and at every step while it is held, [onEnd] once with
 * where it lifted. Nothing while not [enabled].
 */
@Composable
fun Modifier.horizontalScrub(
    enabled: Boolean = true,
    onMove: (Float) -> Unit,
    onEnd: (Float) -> Unit,
): Modifier {
    // Read through state rather than keyed on, so a value the callbacks use
    // changing mid-scrub (the track's length arriving) doesn't end the gesture.
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    return this.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown()
            fun ratioAt(x: Float) = (x / size.width).coerceIn(0f, 1f)
            var ratio = ratioAt(down.position.x)
            move(ratio)
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull() ?: break
                ratio = ratioAt(change.position.x)
                move(ratio)
                if (!change.pressed) break
                change.consume()
            }
            end(ratio)
        }
    }
}

/** Icon and button counterpart to [rowClickable]: the SDK's clickable plus a haptic tick. */
@Composable
fun Modifier.appClickable(onClick: () -> Unit): Modifier {
    val (tick, _) = rememberTapHaptics()
    return this.lightClickable {
        tick()
        onClick()
    }
}

/**
 * A menu row, drawn the way the phone's own menus are: one line for a row that does
 * something, and for a setting its name small above its value large. The line is
 * Copy, one step below the stock menus' Heading, which shouted on a page of rows.
 */
@Composable
fun TextRow(
    title: String,
    modifier: Modifier = Modifier,
    /** A setting's current value, drawn large under the title, which becomes its label. */
    value: String? = null,
    /**
     * A line under the title saying what the row does or how it stands. One line
     * unless [subtitleLines] says otherwise, so a list of names doesn't go ragged.
     */
    subtitle: String? = null,
    subtitleLines: Int = 1,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** The choice in effect on a picker, ticked at the row's end where there is no [trailing]. */
    checked: Boolean = false,
    /**
     * Null for a row that states something rather than opening it (a setting with
     * only one possible value): it takes no press and its main line is dimmed.
     */
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick == null) it else it.rowClickable(onClick) }
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
        when {
            trailing != null -> trailing()
            checked -> LightIcon(LightIcons.ACCEPT, size = CHECK_SCALE)
        }
    }
}

/** The tick's size, as a multiple of the SDK icon's own. */
private const val CHECK_SCALE = 1.4f

/** A menu row's main line, in Light's Copy (see [TextRow]). */
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

/** Where a menu row's text starts: two grid units, as on the phone's own text-only lists. */
const val MENU_INSET_PX = 80

/** Above and below a row's text: with Copy's line, a 136px pitch. */
private const val MENU_PAD_PX = 32

/** Between a leading glyph and the text: one unit. */
private const val MENU_GAP_PX = 40

/**
 * One of Light's centred verbs: a word in capitals on its own line, as on the
 * first-run source list and the Clear a held field offers.
 */
@Composable
fun ChoiceButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .appClickable(onClick = onClick)
            .padding(vertical = px(40)),
        contentAlignment = Alignment.Center,
    ) {
        // Light's Button tracking, but Regular: the style asks for Medium, which
        // the phone lacks, and AppText would round it up to Bold.
        AppText(
            label,
            pxSp(LightType.COPY_PX),
            role = TextRole.Button,
            weight = FontWeight.Normal,
        )
    }
}

/** A short dim line in the middle of a page with nothing else to show yet ("Loading…"). */
@Composable
fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AppText(text, pxSp(LightType.DETAIL_PX), dim = true)
    }
}

@Composable
fun EmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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

/** How long a notice stays: long enough to read. */
const val NOTICE_MS = 2500L

/**
 * A word or two dead centre, above everything else: "Couldn't save", and on a
 * second line why. Inset so a long reason wraps instead of running off the screen.
 */
@Composable
fun BoxScope.NoticePill(text: String) {
    AppText(
        text,
        pxSp(ROW_TITLE_PX),
        align = TextAlign.Center,
        modifier = Modifier
            .align(Alignment.Center)
            .padding(horizontal = px(LIST_EDGE_PX))
            .clip(RoundedCornerShape(px(24)))
            // The page's own background, so it follows the theme and Invert Colours.
            .background(LightThemeTokens.colors.background)
            .padding(horizontal = px(44), vertical = px(28)),
    )
}
