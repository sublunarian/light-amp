package com.sublunar.amp.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.sublunar.amp.App
import com.sublunar.amp.art.ArtworkNeed
import com.sublunar.amp.ui.CANVAS_W_PX
import com.sublunar.amp.ui.LightType
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
    fromSource: String? = null,
) {
    // Hidden artwork draws nothing, not even the placeholder tile.
    if (App.hideArtwork.collectAsState().value) return
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    ArtworkTile(rememberArtwork(coverArtId, sizePx, need, fromSource), size, modifier, corner, fallback)
}

/** [AppArtwork] for a cover its caller has already loaded: the tile, with [fallback] while there is none. */
@Composable
fun ArtworkTile(
    image: ImageBitmap?,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = px(10),
    fallback: ImageVector = AppIcons.MusicNote,
) {
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
    /** The source the cover belongs to, where it isn't the browsed one — see ArtworkLoader.load. */
    coverSource: String? = null,
) {
    val artworkHidden = App.hideArtwork.collectAsState().value
    val oneLine = subtitle.isBlank() && artworkHidden && selected == null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(px(if (oneLine) ROW_TEXT_H_PX else ROW_H_PX))
            .rowClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DownloadMark(downloaded)
        if (selected != null) {
            SelectionArtwork(coverArtId, selected)
            Spacer(Modifier.width(px(ROW_GAP_PX)))
        } else if (!artworkHidden) {
            AppArtwork(coverArtId = coverArtId, size = px(ROW_ART_PX), fallback = fallback, fromSource = coverSource)
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
                    role = TextRole.Detail,
                    dim = true,
                    maxLines = 1,
                )
            }
        }
        // Nothing at the end: the same gap after the text as before it, so a long
        // title stops short of the scroll bar or A–Z strip.
        if (trailing != null) trailing() else Spacer(Modifier.width(px(ROW_GAP_PX)))
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
private fun RowTitle(text: String, maxLines: Int = 1, modifier: Modifier = Modifier) {
    AppText(
        text,
        pxSp(ROW_TITLE_PX),
        lineHeight = pxSp(ROW_TITLE_LINE_PX),
        role = TextRole.Fine,
        maxLines = maxLines,
        modifier = modifier,
    )
}

/**
 * The list's type and pitch, in canvas pixels: a notch under Light's own Music list
 * (whose sizes crowd the screen), sharing its 72px title axis. Shared with the queue
 * and the artist list so every row in the app is the same typography. Six rows fill
 * the space between the header and the bottom bar, the cover 12px from either edge.
 */
const val ROW_H_PX = 152

/** How many lines an artist's name wraps to before it's cut off. */
private const val ARTIST_NAME_LINES = 2

/** A one-line row with no picture (artists, playlists without artwork): eight fill the screen. */
private const val ROW_TEXT_H_PX = 115

/**
 * A row's title and subtitle in the SDK's fine and detail styles: fine carries 3%
 * tracking (see [TextRole.Fine]), and both styles' lines come to about 60px.
 */
const val ROW_TITLE_PX = LightType.FINE_PX
const val ROW_TITLE_LINE_PX = 60
const val ROW_SUB_PX = LightType.DETAIL_PX
const val ROW_SUB_LINE_PX = 60

/** Air above and below a name in a row without a picture: a one-line row is [ROW_TEXT_H_PX]. */
private const val ROW_TEXT_AIR_PX = (ROW_TEXT_H_PX - ROW_TITLE_LINE_PX) / 2

/** Cover size, and the gap beside it (the same gap that separates two covers). */
const val ROW_ART_PX = 128
const val ROW_GAP_PX = 32

/**
 * The leading slot the download mark sits in: with [LIST_EDGE_PX] of list padding,
 * what follows starts 72px from the screen's edge, where Light's own list puts titles.
 */
const val ROW_LEAD_PX = 51

private const val ROW_MARK_PX = 33

/** An action row's icon, centred in the cover's box where a list has covers. */
const val ROW_ACTION_ICON_PX = 57
const val ROW_ACTION_H_PX = 102

/** List padding, so a row's own left edge lines up with Light's music list. */
const val LIST_EDGE_PX = 21

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
    // Both read unconditionally (see DownloadMark). Without pictures the row keeps
    // its pitch, so every list steps alike. The phone's own library has no artist
    // pictures, so it would be a column of empty circles.
    val wantsCovers = !App.hideArtistImages.collectAsState().value
    val local = !App.source.collectAsState().value.hasServer
    val covers = wantsCovers && !local
    Row(
        modifier = modifier
            .fillMaxWidth()
            // With pictures the pitch holds and a second line fits inside it.
            // Without, the row grows by the line, keeping the same air between
            // names whether a name takes one line or two.
            .then(if (covers) Modifier.height(px(ROW_H_PX)) else Modifier.heightIn(min = px(ROW_TEXT_H_PX)))
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
        // A long name takes a second line rather than being cut off.
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = if (covers) px(0) else px(ROW_TEXT_AIR_PX)),
            contentAlignment = Alignment.CenterStart,
        ) {
            RowTitle(name, maxLines = ARTIST_NAME_LINES)
        }
        Spacer(Modifier.width(px(ROW_GAP_PX)))
    }
}

/** An icon and label action row heading a list (Shuffle, New Playlist, Edit…). */
@Composable
fun PlayAllRow(
    icon: ImageVector,
    label: String,
    /**
     * Heads a list of covers, so it takes a song row's height and, while covers
     * show, its icon centres where a cover sits. Otherwise it is shorter: a stack
     * of these at full pitch pushed the list itself off the screen.
     */
    headsList: Boolean = false,
    /** The list it heads is one line a row, so without artwork it takes that list's pitch. */
    oneLineList: Boolean = false,
    onClick: () -> Unit,
) {
    // Read before branching (see DownloadMark).
    val artworkHidden = App.hideArtwork.collectAsState().value
    val heightPx = when {
        !headsList -> ROW_ACTION_H_PX
        oneLineList && artworkHidden -> ROW_TEXT_H_PX
        else -> ROW_H_PX
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = px(heightPx))
            .appClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Starts where the covers start: a line of the list, not a row with an
        // empty sleeve.
        Spacer(Modifier.width(px(ROW_LEAD_PX)))
        ActionButton(icon, label, inCoverBox = headsList && !artworkHidden)
    }
}

/** The Shuffle row heading a list of covers. */
@Composable
fun ShuffleRow(
    label: String = "Shuffle",
    onClick: () -> Unit,
) {
    PlayAllRow(AppIcons.Shuffle, label, headsList = true, onClick = onClick)
}

/**
 * Two action buttons heading a list of covers, on a song row's axes: the left
 * sits as a song row would, its icon where the cover goes; the right repeats
 * that layout from the screen's middle, as if a cover started there.
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
    // Read before branching (see DownloadMark).
    val inCoverBox = !App.hideArtwork.collectAsState().value
    Row(
        modifier = Modifier.fillMaxWidth().height(px(ROW_H_PX)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .width(px(CANVAS_W_PX / 2 - LIST_EDGE_PX))
                .fillMaxHeight()
                .appClickable(onClick = onLeft),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(px(ROW_LEAD_PX)))
            ActionButton(leftIcon, leftLabel, inCoverBox)
        }
        if (rightIcon != null && onRight != null) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .appClickable(onClick = onRight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButton(rightIcon, rightLabel, inCoverBox)
            }
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, inCoverBox: Boolean) {
    if (inCoverBox) {
        Box(Modifier.size(px(ROW_ART_PX)), contentAlignment = Alignment.Center) {
            AppIcon(icon, size = px(ROW_ACTION_ICON_PX))
        }
    } else {
        AppIcon(icon, size = px(ROW_ACTION_ICON_PX))
    }
    Spacer(Modifier.width(px(ROW_GAP_PX)))
    // Not TextRole.Button: Bold at 0.15em shouts on a full-width row label.
    RowTitle(label)
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

/**
 * TRIAL (Use SDK Font Styles): the player card's pair, 48px over 38px, each on its
 * natural line (about 1.21 times the size). Amp's own rows still hold them; the
 * gutter widens to three digits of the bigger number.
 */
private const val SDK_NUMBERED_TITLE_PX = 48
private const val SDK_NUMBERED_TITLE_LINE_PX = 58
private const val SDK_NUMBERED_SUB_PX = 38
private const val SDK_NUMBERED_SUB_LINE_PX = 46
private const val SDK_NUMBERED_ROW_H_PX = NUMBERED_ROW_H_PX
private const val SDK_NUMBERED_ROW_TALL_PX = NUMBERED_ROW_TALL_PX
private const val SDK_NUMBERED_LEAD_PX = 82

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
    val sdk = App.settings.sdkFontStyles.state.collectAsState().value
    val titlePx = if (sdk) SDK_NUMBERED_TITLE_PX else NUMBERED_TITLE_PX
    val subPx = if (sdk) SDK_NUMBERED_SUB_PX else NUMBERED_SUB_PX
    val titleLinePx = if (sdk) SDK_NUMBERED_TITLE_LINE_PX else NUMBERED_TITLE_LINE_PX
    val subLinePx = if (sdk) SDK_NUMBERED_SUB_LINE_PX else NUMBERED_SUB_LINE_PX
    val rowPx = if (sdk) SDK_NUMBERED_ROW_H_PX else NUMBERED_ROW_H_PX
    val tallPx = if (sdk) SDK_NUMBERED_ROW_TALL_PX else NUMBERED_ROW_TALL_PX
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(if (subtitle.isNotBlank()) tallPx else rowPx))
            .rowClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            // Select mode swaps the number gutter for the cover and check bubble.
            SelectionArtwork(coverArtId, selected)
            Spacer(Modifier.width(px(ROW_GAP_PX)))
        }
        val leadPx = if (selected != null) 0 else if (sdk) SDK_NUMBERED_LEAD_PX else NUMBERED_LEAD_PX
        // Lines under the title rather than across the row, so long titles get the
        // whole width. The number reads as the title's, on its baseline, not as
        // the whole block's.
        Column(modifier = Modifier.weight(1f)) {
            Row {
                if (selected == null) {
                    Box(
                        modifier = Modifier
                            .width(px(leadPx))
                            .padding(end = px(NUMBER_GAP_PX))
                            .then(if (current) Modifier.align(Alignment.CenterVertically) else Modifier.alignByBaseline()),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        if (current) {
                            AppIcon(AppIcons.Waveform, size = px(41))
                        } else {
                            AppText(
                                number.toString(),
                                pxSp(subPx),
                                lineHeight = pxSp(subLinePx),
                                role = TextRole.Detail,
                                exact = true,
                                dim = true,
                                align = TextAlign.End,
                            )
                        }
                    }
                }
                AppText(
                    title,
                    pxSp(titlePx),
                    lineHeight = pxSp(titleLinePx),
                    maxLines = 1,
                    exact = true,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            if (subtitle.isNotBlank()) {
                AppText(
                    subtitle,
                    pxSp(subPx),
                    lineHeight = pxSp(subLinePx),
                    dim = true,
                    maxLines = 1,
                    exact = true,
                    modifier = Modifier.padding(start = px(leadPx)),
                )
            }
            AppText(
                detail,
                pxSp(subPx),
                lineHeight = pxSp(subLinePx),
                dim = true,
                maxLines = 1,
                exact = true,
                modifier = Modifier.padding(start = px(leadPx)),
            )
        }
        Spacer(Modifier.width(px(ROW_GAP_PX)))
    }
}

/**
 * TRIAL (Album Shuffle Row): an action heading an album's tracks, on their axes:
 * the icon in the number gutter where a number sits, the label where the titles
 * start, at a track row's height.
 */
@Composable
fun NumberedActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val sdk = App.settings.sdkFontStyles.state.collectAsState().value
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(if (sdk) SDK_NUMBERED_ROW_H_PX else NUMBERED_ROW_H_PX))
            .appClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(px(if (sdk) SDK_NUMBERED_LEAD_PX else NUMBERED_LEAD_PX))
                .padding(end = px(NUMBER_GAP_PX)),
            contentAlignment = Alignment.CenterEnd,
        ) {
            // The playing track's waveform size, the gutter's one glyph.
            AppIcon(icon, size = px(41))
        }
        AppText(
            label,
            pxSp(if (sdk) SDK_NUMBERED_TITLE_PX else NUMBERED_TITLE_PX),
            lineHeight = pxSp(if (sdk) SDK_NUMBERED_TITLE_LINE_PX else NUMBERED_TITLE_LINE_PX),
            maxLines = 1,
            exact = true,
        )
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
