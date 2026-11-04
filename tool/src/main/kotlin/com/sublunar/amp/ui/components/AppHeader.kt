package com.sublunar.amp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons

/**
 * The corner button: what a tap does, and optionally what a hold does (on library
 * pages, Settings, instead of a gear on every page). [onLongClick] comes before
 * [onClick] so the trailing lambda every call site uses is the tap.
 */
data class HeaderAction(
    val icon: ImageVector,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/**
 * The inner slot beside the right corner: search on the tabs, delete while
 * selecting. The two read as a pair: the corner keeps its full square (icon
 * centred at 1000px), and the inner slot is an 80px square hard against it, its
 * icon 120px to the left. The hit boxes meet at 920px without overlapping.
 */
const val INNER_SLOT_PX = 80

/** The corner glyph's box, the same size the player's header gives its More. */
const val CORNER_ICON_PX = 66

/**
 * A header's title at the largest size it fits at, a pixel at a time down from
 * fine's 52px to [SDK_TITLE_MIN_PX], so a long one fills the
 * header rather than dropping a whole SDK step. Below the full size the sizes are
 * drawn as asked, not snapped back to an SDK style. Each is measured in the style
 * it would be drawn in. One that fits at none is cut off at the smallest: it
 * neither shrinks to fine print nor wraps.
 */
@Composable
private fun HeaderTitle(title: String, fit: Boolean, roomPx: Int) {
    val full = LightType.FINE_PX
    val sizes = if (fit) (full downTo SDK_TITLE_MIN_PX).toList() else listOf(full)
    val styles = sizes.map {
        appTextStyle(pxSp(it), role = shrunkRole(it, full), align = TextAlign.Center, exact = it != full)
    }
    val measurer = rememberTextMeasurer()
    val step = if (roomPx <= 0) {
        0
    } else {
        remember(title, roomPx, styles) {
            styles.indexOfFirst { style ->
                !measurer.measure(
                    AnnotatedString(title),
                    style = style,
                    maxLines = 1,
                    constraints = Constraints(maxWidth = roomPx),
                ).hasVisualOverflow
            }
        }
    }
    val size = if (step < 0) sizes.last() else sizes[step]
    AppText(
        title,
        pxSp(size),
        role = shrunkRole(size, full),
        maxLines = 1,
        align = TextAlign.Center,
        exact = size != full,
    )
}

/**
 * A shrunk title's role: detail's, with no extra spacing, so it reads as the
 * page's title rather than the player's spaced-out card.
 */
private fun shrunkRole(size: Int, full: Int): TextRole =
    if (size == full) TextRole.Subheading else TextRole.Detail

/** The smallest a header title shrinks to: the player card's title. Below it, it's cut off. */
private const val SDK_TITLE_MIN_PX = 48

/** How long a title takes to fade in or out of the header. */
private const val TITLE_FADE_MS = 200

/**
 * Header height in LP3 pixels, the same 4 grid units as the bottom bars. Fixed, not
 * `screenHeight − screenWidth`: that gives less wherever the reported height
 * excludes insets (the emulator), and the corner buttons stop being squares.
 */
const val HEADER_BAR_PX = 160

/**
 * The header every page wears: a [HEADER_BAR_PX] band with a full-height square at
 * each end so hit zones line up. Left is back or a custom action, centre a title or
 * custom content, right an optional action, with an optional [INNER_SLOT_PX] slot
 * beside it.
 */
@Composable
fun AppHeader(
    title: String? = null,
    titleContent: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    leftAction: HeaderAction? = null,
    rightAction: HeaderAction? = null,
    innerAction: HeaderAction? = null,
    /**
     * Let a title that will not fit drop to a smaller size rather than being cut.
     * Library pages only: elsewhere titles are the app's own short words.
     */
    fitTitle: Boolean = false,
    /** Hides the title while the page shows it elsewhere (the album page's card), fading between. */
    titleShown: Boolean = true,
) {
    val titleAlpha by animateFloatAsState(
        targetValue = if (titleShown) 1f else 0f,
        animationSpec = tween(TITLE_FADE_MS),
        label = "headerTitle",
    )
    // The corner slots are 160px squares, so their hit boxes match the transport's
    // and the icons land on the shared 80px axis.
    val headerHeight = px(HEADER_BAR_PX)

    Row(
        modifier = Modifier.fillMaxWidth().height(headerHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderSlot(headerHeight, headerHeight, onClick = leftAction?.onClick ?: onBack) {
            when {
                leftAction != null -> AppIcon(leftAction.icon, size = px(71))
                // The phone's own chevron, centred in the corner square, lands
                // where the stock tools' headers draw theirs.
                onBack != null -> LightIcon(LightIcons.BACK, size = 2f)
                else -> {}
            }
        }
        // Balances the inner slot's width on the right, so the title stays
        // centred on screen.
        if (innerAction != null) Spacer(Modifier.width(px(INNER_SLOT_PX)).height(headerHeight))

        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            val room = constraints.maxWidth
            when {
                titleContent != null -> titleContent()
                title != null -> Box(Modifier.alpha(titleAlpha)) { HeaderTitle(title, fitTitle, room) }
                else -> {}
            }
        }

        if (innerAction != null) {
            HeaderSlot(
                px(INNER_SLOT_PX),
                px(INNER_SLOT_PX),
                onClick = innerAction.onClick,
                onLongClick = innerAction.onLongClick,
            ) {
                AppIcon(innerAction.icon, size = px(66))
            }
        }
        HeaderSlot(
            headerHeight,
            headerHeight,
            onClick = rightAction?.onClick,
            onLongClick = rightAction?.onLongClick,
        ) {
            if (rightAction != null) {
                AppIcon(rightAction.icon, size = px(CORNER_ICON_PX))
            }
        }
    }
}

@Composable
private fun HeaderSlot(
    width: Dp,
    height: Dp,
    onClick: (() -> Unit)?,
    /** A hold, where the slot has one: the same slow press every row uses. */
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val press = when {
        onLongClick != null -> Modifier.rowClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
        onClick != null -> Modifier.appClickable(onClick = onClick)
        else -> Modifier
    }
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .then(press),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
