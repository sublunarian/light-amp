package com.sublunar.amp.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.sublunar.amp.App
import com.sublunar.amp.art.ArtworkNeed
import com.sublunar.amp.ui.CANVAS_W_PX
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.ui.LightThemeTokens

@Composable
fun AppArtwork(
    coverArtId: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = px(10),
    fallback: ImageVector = AppIcons.MusicNote,
    need: ArtworkNeed = ArtworkNeed.BROWSING,
) {
    // Hidden artwork draws nothing, not even the placeholder tile.
    if (App.hideArtwork.collectAsState().value) return
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val image = rememberArtwork(coverArtId, sizePx, need)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
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
            AppIcon(fallback, size = size * 0.42f, tint = LightThemeTokens.colors.contentSecondary)
        }
    }
}

// Faint enough for the check bubble to read clearly, still recognizable as the cover.
private const val EDIT_ARTWORK_ALPHA = 0.55f

/** The artwork every select-mode row wears: a dimmed cover with the check bubble centred on it. */
@Composable
fun SelectionArtwork(coverArtId: String?, selected: Boolean) {
    Box(Modifier.size(px(ROW_ART_PX)), contentAlignment = Alignment.Center) {
        AppArtwork(coverArtId, size = px(ROW_ART_PX), modifier = Modifier.alpha(EDIT_ARTWORK_ALPHA))
        AppIcon(
            if (selected) AppIcons.Selected else AppIcons.Unselected,
            size = px(66),
            tint = if (selected) {
                LightThemeTokens.colors.content
            } else {
                LightThemeTokens.colors.contentSecondary
            },
        )
    }
}

/**
 * A list row in the Light music app's proportions: title at [ROW_TITLE_PX], a dimmer
 * subtitle at [ROW_SUB_PX] under it, on the [ROW_H_PX] pitch. The download mark leads
 * the row, where Light puts it (at the far end it read as a control).
 */
@Composable
fun TrackRow(
    title: String,
    subtitle: String,
    coverArtId: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fallback: ImageVector = AppIcons.MusicNote,
    onLongClick: (() -> Unit)? = null,
    /** A downloaded song or a complete album. */
    downloaded: Boolean = false,
    /** Selection state, or null when the list isn't in multi-select mode. */
    selected: Boolean? = null,
    /** At the row's end, such as a drag handle. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val artworkHidden = App.hideArtwork.collectAsState().value
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(px(ROW_H_PX))
            .rowClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DownloadMark(downloaded)
        if (selected != null) {
            SelectionArtwork(coverArtId, selected)
            Spacer(Modifier.width(px(ROW_GAP_PX)))
        } else if (!artworkHidden) {
            AppArtwork(coverArtId = coverArtId, size = px(ROW_ART_PX), fallback = fallback)
            // Only beside artwork: with none, the title belongs against the mark.
            Spacer(Modifier.width(px(ROW_GAP_PX)))
        }
        Column(modifier = Modifier.weight(1f)) {
            RowTitle(title)
            if (subtitle.isNotBlank()) {
                AppText(
                    subtitle,
                    pxSp(ROW_SUB_PX),
                    lineHeight = pxSp(ROW_SUB_LINE_PX),
                    dim = true,
                    maxLines = 1,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * The leading slot every list row keeps, filled with the download mark when there
 * is one: it keeps every list's titles on one axis whether or not it is filled.
 */
@Composable
private fun DownloadMark(downloaded: Boolean) {
    // Read before branching: behind an `if`, collectAsState stops updating.
    val marks = !App.settings.hideDownloadIcons.state.collectAsState().value
    Box(modifier = Modifier.width(px(ROW_LEAD_PX))) {
        if (downloaded && marks) AppIcon(AppIcons.Downloaded, size = px(ROW_MARK_PX))
    }
}

@Composable
private fun RowTitle(text: String) {
    AppText(text, pxSp(ROW_TITLE_PX), lineHeight = pxSp(ROW_TITLE_LINE_PX), maxLines = 1)
}

/**
 * The list's type and pitch, in canvas pixels: a notch under Light's own Music list
 * (whose sizes crowd the screen), sharing its 72px title axis. Shared with the queue
 * and the artist list so every row in the app is the same typography.
 */
const val ROW_H_PX = 150

/** A row carrying one line only, as [ArtistRow] without pictures. */
const val ROW_SINGLE_H_PX = 108
const val ROW_TITLE_PX = 54
const val ROW_TITLE_LINE_PX = 66
const val ROW_SUB_PX = 39
const val ROW_SUB_LINE_PX = 48

/** Cover size, and the gap beside it (the same gap that separates two covers). */
const val ROW_ART_PX = 128
const val ROW_GAP_PX = 32

/**
 * The leading slot the download mark sits in: with [LIST_EDGE_PX] of list padding,
 * what follows starts 72px from the screen's edge, where Light's own list puts titles.
 */
const val ROW_LEAD_PX = 51

private const val ROW_MARK_PX = 33

/** An action row's icon and gap: smaller than a list row's, so a pair's labels fit side by side. */
const val ROW_ACTION_ICON_PX = 57
private const val ACTION_GAP_PX = 24
const val ROW_ACTION_H_PX = 102

/**
 * Where the left of a pair centres, the right mirrored across the screen: a fifth
 * of the width either side of the middle reads as one control with two halves.
 */
private const val SPLIT_LEFT_X_PX = CANVAS_W_PX * 3 / 10

/** As wide as a pair's slots can be without overlapping. */
private const val ACTION_SLOT_PX = CANVAS_W_PX - SPLIT_LEFT_X_PX * 2

/** A button with no neighbour gets the room a long label wants. */
private const val SOLO_SLOT_PX = 600

/** List padding, so a row's own left edge lines up with Light's music list. */
const val LIST_EDGE_PX = 21

/**
 * Top padding, measured so the action row heading a library list sits evenly
 * between the header and the first cover below it.
 */
const val LIST_TOP_PX = 15

/** An artist row: a track row's metrics, with the artist's picture where a server has one. */
@Composable
fun ArtistRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** Every track of the artist's is downloaded. */
    downloaded: Boolean = false,
    /** The server's picture of them, where it has one (Artist.imageId). */
    imageId: String? = null,
) {
    // Both read unconditionally (see DownloadMark). Without pictures the row is one
    // line at the tighter pitch. The phone's own library has no artist pictures,
    // so it would be a column of empty circles.
    val wantsCovers = !App.hideArtistImages.collectAsState().value
    val local = !App.source.collectAsState().value.hasServer
    val covers = wantsCovers && !local
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(px(if (covers) ROW_H_PX else ROW_SINGLE_H_PX))
            .rowClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DownloadMark(downloaded)
        if (covers) {
            // Round where an album is square, so the shape says what this is and a
            // face doesn't read as a sleeve that failed to load.
            AppArtwork(
                imageId,
                size = px(ROW_ART_PX),
                corner = px(ROW_ART_PX) / 2,
                fallback = AppIcons.RecordVoiceOver,
            )
            Spacer(Modifier.width(px(ROW_GAP_PX)))
        }
        RowTitle(name)
    }
}

/** An icon and label action row heading a list (Shuffle, New Playlist, Edit…). */
@Composable
fun PlayAllRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Shorter than a track row: a stack of these at full pitch pushed the
            // list itself off the screen.
            .heightIn(min = px(ROW_ACTION_H_PX))
            .appClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Starts where the covers start: a line of the list, not a row with an
        // empty sleeve.
        Spacer(Modifier.width(px(ROW_LEAD_PX)))
        AppIcon(icon, size = px(ROW_ACTION_ICON_PX))
        Spacer(Modifier.width(px(ROW_GAP_PX)))
        // Not TextRole.Button: Bold at 0.15em shouts on a full-width row label.
        RowTitle(label)
    }
}

/**
 * Two action buttons side by side, each centred in its own half. The left one keeps
 * its slot when there is no right one, so every such row starts on the same axis.
 */
@Composable
fun SplitActionRow(
    leftIcon: ImageVector,
    leftLabel: String,
    onLeft: () -> Unit,
    /**
     * The right-hand button, or null. The left one stays put either way, so a page
     * whose second button arrives later (an artist's popular tracks) doesn't jump.
     */
    rightIcon: ImageVector? = null,
    rightLabel: String = "",
    onRight: (() -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxWidth().heightIn(min = px(ROW_ACTION_H_PX))) {
        if (rightIcon != null && onRight != null) {
            ActionSlot(SPLIT_LEFT_X_PX, leftIcon, leftLabel, onLeft)
            ActionSlot(CANVAS_W_PX - SPLIT_LEFT_X_PX, rightIcon, rightLabel, onRight)
        } else {
            ActionSlot(SPLIT_LEFT_X_PX, leftIcon, leftLabel, onLeft, SOLO_SLOT_PX)
        }
    }
}

/**
 * One action button, centred on an absolute position across the screen rather than
 * laid out in a weighted Row: a list's end padding depends on what runs down its
 * right edge (the A–Z strip or the scroll bar), so [centreX] is measured from the
 * screen's left edge and [LIST_EDGE_PX], where list content begins, comes off it.
 * The slot stays narrow enough that the parent's bounds don't clip its tap target.
 */
@Composable
private fun BoxScope.ActionSlot(
    centreX: Int,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    width: Int = ACTION_SLOT_PX,
) {
    Box(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .offset(x = px(centreX - LIST_EDGE_PX - width / 2))
            .width(px(width))
            .heightIn(min = px(ROW_ACTION_H_PX))
            .appClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icon, size = px(ROW_ACTION_ICON_PX))
            Spacer(Modifier.width(px(ACTION_GAP_PX)))
            RowTitle(label)
        }
    }
}

/**
 * An album's own tracks, a notch smaller than a library list: a running order
 * under the album's card, not a list to browse.
 */
private const val NUMBERED_TITLE_PX = 48
private const val NUMBERED_TITLE_LINE_PX = 60
private const val NUMBERED_SUB_PX = 36
private const val NUMBERED_SUB_LINE_PX = 45

/** The row's height with one line under the title, and with two. */
private const val NUMBERED_ROW_H_PX = 132
private const val NUMBERED_ROW_TALL_PX = 177

/** Air between a number's right edge and the title axis. */
private const val NUMBER_GAP_PX = 15

/**
 * The number gutter: three digits of [NUMBERED_SUB_PX] type plus [NUMBER_GAP_PX],
 * one width for every album, like a printed tracklist's margin. The lists' narrower
 * leading slot only fit a number shrunken and hard against the title.
 */
private const val NUMBERED_LEAD_PX = 78

/** Track number (or the waveform while it plays), title, and the lines under it. */
@Composable
fun NumberedRow(
    number: Int,
    title: String,
    /**
     * Who plays on the song. Blank drops the line and the row closes up: the
     * caller sends a credit only when it says something the page hasn't.
     */
    subtitle: String,
    /** The last line, the track's length. */
    detail: String,
    onClick: () -> Unit,
    current: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    /** Selection state, or null when the list isn't in multi-select mode. */
    selected: Boolean? = null,
    /** The row's cover, needed only while selecting. */
    coverArtId: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(if (subtitle.isNotBlank()) NUMBERED_ROW_TALL_PX else NUMBERED_ROW_H_PX))
            .rowClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            // Select mode swaps the number gutter for the cover and check bubble.
            SelectionArtwork(coverArtId, selected)
            Spacer(Modifier.width(px(ROW_GAP_PX)))
        } else {
            Box(
                modifier = Modifier
                    .width(px(NUMBERED_LEAD_PX))
                    .padding(end = px(NUMBER_GAP_PX)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (current) {
                    AppIcon(AppIcons.Waveform, size = px(41))
                } else {
                    AppText(
                        number.toString(),
                        pxSp(NUMBERED_SUB_PX),
                        lineHeight = pxSp(NUMBERED_SUB_LINE_PX),
                        role = TextRole.Detail,
                        dim = true,
                        align = TextAlign.End,
                    )
                }
            }
        }
        // Lines under the title rather than across the row, so long titles get the
        // whole width.
        Column(modifier = Modifier.weight(1f)) {
            AppText(
                title,
                pxSp(NUMBERED_TITLE_PX),
                lineHeight = pxSp(NUMBERED_TITLE_LINE_PX),
                maxLines = 1,
            )
            if (subtitle.isNotBlank()) {
                AppText(
                    subtitle,
                    pxSp(NUMBERED_SUB_PX),
                    lineHeight = pxSp(NUMBERED_SUB_LINE_PX),
                    dim = true,
                    maxLines = 1,
                )
            }
            AppText(
                detail,
                pxSp(NUMBERED_SUB_PX),
                lineHeight = pxSp(NUMBERED_SUB_LINE_PX),
                dim = true,
                maxLines = 1,
            )
        }
    }
}

/** Padding shared by every list in the app (see [LIST_EDGE_PX] and [ROW_LEAD_PX]). */
@Composable
fun listPadding(
    end: Dp = px(LIST_EDGE_PX),
): PaddingValues =
    PaddingValues(
        start = px(LIST_EDGE_PX),
        end = end,
        top = px(LIST_TOP_PX),
        bottom = px(LIST_TOP_PX),
    )

/**
 * A list filling its parent, with a scroll bar down its right edge and its
 * content inset by the bar's lane. The tab lists have the A–Z strip instead.
 */
@Composable
fun ScrollableList(
    /** For a caller driving the scroll itself (drag auto-scroll), or [LibraryList]'s remembered one. */
    state: LazyListState = rememberLazyListState(),
    /** False for the few lists without the bar and its lane. */
    scrollBar: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = listPadding(
                end = if (scrollBar) px(SCROLLBAR_LANE_PX) else px(LIST_EDGE_PX),
            ),
            content = content,
        )
        if (scrollBar) ListScrollBar(state)
    }
}

/** A library list that remembers where it was (see [rememberListAnchor]). */
@Composable
fun LibraryList(
    anchor: String,
    headerCount: Int = 0,
    scrollBar: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    ScrollableList(rememberListAnchor(anchor, headerCount), scrollBar, content)
}
