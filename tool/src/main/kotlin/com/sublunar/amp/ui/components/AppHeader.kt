package com.sublunar.amp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.runtime.remember
import com.sublunar.amp.ui.n
import com.sublunar.amp.ui.nSp
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp

data class HeaderAction(val icon: ImageVector, val onClick: () -> Unit)

/**
 * Right-hand geometry when a search button is present.
 *
 * Search and now-playing read as a pair, so they sit a half-pitch apart: the
 * now-playing icon keeps the shared [EDGE_INSET_PX] axis (80px in from the right,
 * so centred at 1000px) with the full 160px square around it, and search centres
 * 120px to its left, at 880px, inside an 80px square.
 *
 * The two hit boxes meet at 920px without overlapping: search covers 840–920 and
 * now-playing 920–1080.
 */
private const val EDGE_INSET_PX = 80
private const val SEARCH_SLOT_PX = 80
private const val NOW_PLAYING_SLOT_PX = 160

/** Gap between the two icon centres. */
private const val SEARCH_TO_NOW_PLAYING_PX = 120

/**
 * The corner glyph's own box, in physical pixels — the same 66px the player's
 * header gives its More, so the two corners draw the same size as well as the
 * same shape. n(26) came to 66.3px, and a third of a pixel is enough to put a
 * one-pixel stem between two of them and take the edge off it.
 */
const val CORNER_ICON_PX = 66

/** Nudge that puts the back chevron on the same axis as the other edge controls. */
private val BACK_ICON_BIAS = px(15)

/**
 * A header's title, dropped a size only when the full one will not fit.
 *
 * Measured rather than inferred from the length: the face is proportional, so
 * two titles of the same character count are nothing like the same width, and
 * the only honest test is to lay the string out and ask. It is measured in the
 * very style it will be drawn in ([appTextStyle]) — measuring in a near-enough
 * style is answering the question about a different string — and in the room
 * actually left after whatever shares the slot.
 *
 * Measured rather than drawn-then-corrected: laying it out large, noticing the
 * overflow and recomposing smaller would show one frame of the wrong size on
 * every title that needs shrinking.
 *
 * The smaller size is the player's title-card size, so the two places in the app
 * that show a long name agree with each other.
 */
@Composable
private fun HeaderTitle(title: String, fit: Boolean, roomPx: Int) {
    val full = nSp(20)
    val style = appTextStyle(full, role = TextRole.Subheading, align = TextAlign.Center)
    val measurer = rememberTextMeasurer()
    val fits = !fit || roomPx <= 0 || remember(title, roomPx, style) {
        !measurer.measure(
            AnnotatedString(title),
            style = style,
            maxLines = 1,
            constraints = Constraints(maxWidth = roomPx),
        ).hasVisualOverflow
    }
    AppText(
        title,
        if (fits) full else pxSp(SUBPAGE_TITLE_PX),
        lineHeight = if (fits) TextUnit.Unspecified else pxSp(SUBPAGE_TITLE_LINE_PX),
        role = TextRole.Subheading,
        maxLines = 1,
        align = TextAlign.Center,
    )
}

/** The size a title that will not fit drops to — the player's title card. */
private const val SUBPAGE_TITLE_PX = 45
private const val SUBPAGE_TITLE_LINE_PX = 54

/**
 * Header height in LP3 pixels — 160px, the same 4 grid units as the bottom bars.
 *
 * Fixed rather than derived from `screenHeight − screenWidth`: that formula gives
 * 160px on the LP3 but a smaller value wherever the reported height excludes
 * insets (the emulator), which made the edge buttons stop being 160×160 squares.
 * On the LP3 this is the same number, and it also leaves the content area exactly
 * 1080 × 1080 — a full-width square for artwork.
 */
const val HEADER_BAR_PX = 160

/**
 * Top header matching the original app: one tall band (screen height − width,
 * min n(52)), every button a full-height square so hit zones line up. Left is
 * back or a custom action, centre is a title or custom content, right is an
 * optional action; a search icon can sit left of the right action.
 */
@Composable
fun AppHeader(
    title: String? = null,
    titleContent: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    leftAction: HeaderAction? = null,
    rightAction: HeaderAction? = null,
    searchAction: (() -> Unit)? = null,
    /** What the [searchAction] slot draws; the slot's geometry never changes. */
    searchIcon: ImageVector = AppIcons.Search,
    /**
     * A second left-hand button, in the mirror image of the search slot.
     *
     * Used by the library's liked/all switch, which pairs with the sort button
     * exactly as search pairs with now-playing on the other side.
     */
    secondaryLeftAction: HeaderAction? = null,
    /**
     * Let a title that will not fit drop to a smaller size rather than being cut.
     *
     * Library pages only, and off by default: elsewhere the titles are the app's
     * own short words, where a second size would be variation without a reason.
     */
    fitTitle: Boolean = false,
) {
    // Left and right slots are 160px squares, so their hit boxes match the
    // transport's and the icons land on the shared 80px axis. The right slot
    // narrows when search is present (see the constants above).
    val headerHeight = px(HEADER_BAR_PX)
    val hasSearch = searchAction != null
    // Now-playing keeps a full 160px square centred on its own icon, so no shift.
    val rightSlot = if (hasSearch) px(NOW_PLAYING_SLOT_PX) else headerHeight
    // Search centres SEARCH_TO_NOW_PLAYING_PX to its left; the right slot already
    // reaches half its width past that centre, so this is the leftover gap.
    val searchGap = px(SEARCH_TO_NOW_PLAYING_PX - NOW_PLAYING_SLOT_PX / 2 - SEARCH_SLOT_PX / 2)
    // Each side's extra width beyond its 160px corner square. Whichever side has
    // less gets the difference as a spacer, so the title stays centred on screen
    // whether one, both or neither of the inner slots is present.
    val extraSlot = px(SEARCH_SLOT_PX) + searchGap
    val zero = 0.dp
    val leadingSpacer = if (hasSearch && secondaryLeftAction == null) extraSlot else zero
    val trailingSpacer = if (secondaryLeftAction != null && !hasSearch) extraSlot else zero

    Row(
        modifier = Modifier.fillMaxWidth().height(headerHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderSquare(headerHeight, onClick = leftAction?.onClick ?: onBack) {
            when {
                leftAction != null -> AppIcon(leftAction.icon, size = n(28))
                // ArrowBackIos draws its chevron left of centre inside the glyph
                // box, leaving it ~15px inboard of every other edge control.
                onBack != null -> AppIcon(
                    AppIcons.ArrowBackIos,
                    size = n(22),
                    modifier = Modifier.offset(x = BACK_ICON_BIAS),
                )
                else -> {}
            }
        }

        if (secondaryLeftAction != null) {
            // The mirror of the search slot: an 80px square hard against the
            // corner square, so its glyph centres 200px from the left edge just
            // as search centres 200px from the right.
            Spacer(Modifier.width(searchGap).height(headerHeight))
            HeaderSlot(
                px(SEARCH_SLOT_PX),
                px(SEARCH_SLOT_PX),
                onClick = secondaryLeftAction.onClick,
            ) {
                AppIcon(secondaryLeftAction.icon, size = n(26))
            }
        }
        Spacer(Modifier.width(leadingSpacer).height(headerHeight))

        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            val room = constraints.maxWidth
            when {
                titleContent != null -> titleContent()
                // The title names the page and does nothing else. It used to
                // open a menu, marked by a chevron beside it — an affordance
                // small enough to miss, for one of three scattered ways into
                // what is now a single list of rows on More.
                title != null -> HeaderTitle(title, fitTitle, room)
                else -> {}
            }
        }

        if (searchAction != null) {
            // An 80px square, not the full header height, per the design.
            HeaderSlot(px(SEARCH_SLOT_PX), px(SEARCH_SLOT_PX), onClick = searchAction) {
                AppIcon(searchIcon, size = n(26))
            }
            Spacer(Modifier.width(searchGap).height(headerHeight))
        }
        Spacer(Modifier.width(trailingSpacer).height(headerHeight))
        HeaderSlot(rightSlot, headerHeight, onClick = rightAction?.onClick) {
            if (rightAction != null) {
                AppIcon(rightAction.icon, size = px(CORNER_ICON_PX))
            }
        }
    }
}

@Composable
private fun HeaderSquare(size: Dp, onClick: (() -> Unit)?, content: @Composable () -> Unit) =
    HeaderSlot(width = size, height = size, onClick = onClick, content = content)

@Composable
private fun HeaderSlot(
    width: Dp,
    height: Dp,
    onClick: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .then(if (onClick != null) Modifier.appClickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
