package com.sublunar.amp.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sublunar.amp.App
import com.sublunar.amp.art.ArtworkNeed
import com.sublunar.amp.ui.CANVAS_W_PX
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightThemeTokens

@Composable
fun AppArtwork(
    coverArtId: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = px(COVER_CORNER_PX),
    fallback: ImageVector = AppIcons.MusicNote,
    need: ArtworkNeed = ArtworkNeed.BROWSING,
    fromSource: String? = null,
) {
    // Hidden artwork draws nothing, not even the placeholder tile.
    if (App.hideArtwork.collectAsState().value) return
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    ArtworkTile(rememberArtwork(coverArtId, sizePx, need, fromSource), size, modifier, corner, fallback)
}

private const val COVER_CORNER_PX = 10

/** [AppArtwork] for a cover its caller has already loaded: the tile, with [fallback] while there is none. */
@Composable
fun ArtworkTile(
    image: ImageBitmap?,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = px(COVER_CORNER_PX),
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
    val covers = !App.hideListArtwork.collectAsState().value
    Box(Modifier.size(px(ROW_ART_PX)), contentAlignment = Alignment.Center) {
        if (covers) AppArtwork(coverArtId, size = px(ROW_ART_PX), modifier = Modifier.alpha(EDIT_ARTWORK_ALPHA))
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
    /** TRIAL (Tall Album Rows): five to the page rather than six, the cover grown with the row. */
    tall: Boolean = false,
) {
    val artworkHidden = App.hideListArtwork.collectAsState().value
    val oneLine = subtitle.isBlank() && artworkHidden && selected == null
    val type = rowType(artless = artworkHidden)
    val big = tall && !artworkHidden
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(px(if (big) TALL_ROW_H_PX else if (oneLine) type.oneLinePx else type.rowPx))
            .rowClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Beside a cover the mark keeps to the row's middle, as the cover does.
        val titleRisePx = if (subtitle.isNotBlank()) type.subLinePx / 2 else 0
        DownloadMark(downloaded, onTitleLine = artworkHidden && selected == null, titleRisePx = titleRisePx)
        if (selected != null) {
            SelectionArtwork(coverArtId, selected)
            Spacer(Modifier.width(px(ROW_GAP_PX)))
        } else if (!artworkHidden) {
            AppArtwork(
                coverArtId = coverArtId,
                size = px(if (big) TALL_ROW_ART_PX else ROW_ART_PX),
                fallback = fallback,
                fromSource = coverSource,
            )
            // Only beside artwork: with none, the title belongs against the mark.
            Spacer(Modifier.width(px(ROW_GAP_PX)))
        }
        Column(modifier = Modifier.weight(1f)) {
            RowTitle(title, type = type)
            if (subtitle.isNotBlank()) RowSubtitle(subtitle, type)
        }
        EndDownloadMark(downloaded, titleRisePx)
        trailing?.invoke()
    }
}

/**
 * The leading slot every list row keeps, filled with the download mark when there
 * is one: it keeps every list's titles on one axis whether or not it is filled.
 * The row centres it. [onTitleLine] (lists without covers) moves it onto the
 * title instead: up by [titleRisePx], half of what sits under the title's line,
 * then down to the middle of the title's letters, which sit low in their line,
 * as the stock tools set it.
 */
@Composable
private fun DownloadMark(downloaded: Boolean, onTitleLine: Boolean = false, titleRisePx: Int = 0) {
    val shiftPx = if (onTitleLine) MARK_INK_DROP_PX - titleRisePx else 0
    // TRIAL (40px List Margin): the slot shrinks to the margin and the mark moves
    // to the row's end — see [EndDownloadMark].
    val narrow = narrowListMargin()
    Box(modifier = Modifier.offset(y = px(shiftPx)).width(px(rowLeadPx()))) {
        if (!narrow) DownloadGlyph(downloaded)
    }
}

/**
 * TRIAL (40px List Margin): the download mark at the row's end, on the title's
 * line and in the SDK's grey, as an album page sets its tracks'.
 */
@Composable
private fun EndDownloadMark(downloaded: Boolean, titleRisePx: Int) {
    val narrow = narrowListMargin()
    val marks = !App.settings.hideDownloadIcons.state.collectAsState().value
    if (!narrow || !downloaded || !marks) return
    Spacer(Modifier.width(px(ROW_GAP_PX)))
    DownloadGlyph(downloaded, Modifier.offset(y = px(MARK_INK_DROP_PX - titleRisePx)), grey = true)
}

/** TRIAL (40px List Margin): whether the library's lists start 40px from the screen's edge. */
@Composable
fun narrowListMargin(): Boolean = App.settings.narrowListMargin.state.collectAsState().value

/** Where a list row's content starts inside the list: [ROW_LEAD_PX], or with 40px List Margin on, 40px from the screen's edge. */
@Composable
fun rowLeadPx(): Int = if (narrowListMargin()) NARROW_ROW_LEAD_PX else ROW_LEAD_PX

/** The download mark itself, where [downloaded] and the marks aren't switched off. */
@Composable
private fun DownloadGlyph(downloaded: Boolean, modifier: Modifier = Modifier, grey: Boolean = false) {
    val marks = !App.settings.hideDownloadIcons.state.collectAsState().value
    if (!downloaded || !marks) return
    // Light's own arrow (as the stock Podcasts tool marks a download), at one
    // grid unit, white as the row's own text.
    Icon(
        painterResource(LightIcons.DOWNLOADED_ARROW.drawableResource),
        contentDescription = null,
        tint = if (grey) LightThemeTokens.colors.contentSecondary else LightThemeTokens.colors.content,
        modifier = modifier.size(1f.gridUnitsAsDp()),
    )
}

@Composable
private fun RowTitle(
    text: String,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
    type: RowType = ROW_TYPE,
    onLines: (Int) -> Unit = {},
) {
    AppText(
        text,
        pxSp(type.titlePx),
        lineHeight = pxSp(type.titleLinePx),
        role = type.titleRole,
        maxLines = maxLines,
        modifier = modifier,
        onTextLayout = { onLines(it.lineCount) },
    )
}

@Composable
fun RowSubtitle(text: String, type: RowType = ROW_TYPE) {
    AppText(
        text,
        pxSp(type.subPx),
        lineHeight = pxSp(type.subLinePx),
        role = type.subRole,
        dim = true,
        maxLines = 1,
    )
}

/** A list row's two lines: each one's size, line and SDK role. */
class RowType(
    val titlePx: Int,
    val titleLinePx: Int,
    val titleRole: TextRole,
    val subPx: Int,
    val subLinePx: Int,
    val subRole: TextRole,
    /** The pitch of a row with a second line (songs, albums). */
    val rowPx: Int,
    /** The pitch of a one-line row (artists, playlists). */
    val oneLinePx: Int,
)

private val ROW_TYPE = RowType(
    ROW_TITLE_PX, ROW_TITLE_LINE_PX, TextRole.Fine,
    ROW_SUB_PX, ROW_SUB_LINE_PX, TextRole.Detail,
    ROW_H_PX, ROW_TEXT_H_PX,
)

/**
 * Without covers, the title a step up, to copy, in the room a cover took; the
 * line under it stays the list's own. On a pitch that fills the 920px between
 * the header and the tab bar with whole rows: six with a second line, seven of one.
 */
private val ARTLESS_ROW_TYPE = RowType(
    LightType.COPY_PX, LightType.COPY_LINE_PX, TextRole.Copy,
    ROW_SUB_PX, ROW_SUB_LINE_PX, TextRole.Detail,
    rowPx = 153,
    oneLinePx = 131,
)

/** The list's type: with covers, the list's own; without, its title a step up. */
fun rowType(artless: Boolean): RowType {
    return if (artless) ARTLESS_ROW_TYPE else ROW_TYPE
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


/** Cover size, and the gap beside it (the same gap that separates two covers). */
const val ROW_ART_PX = 128

/**
 * TRIAL (Tall Album Rows): five rows in the 920px between the header and the bar
 * rather than six, the cover keeping its 12px from the row's edges.
 */
internal const val TALL_ROW_H_PX = 184
private const val TALL_ROW_ART_PX = TALL_ROW_H_PX - (ROW_H_PX - ROW_ART_PX)
const val ROW_GAP_PX = 32

/**
 * The leading slot the download mark sits in: with [LIST_EDGE_PX] of list padding,
 * what follows starts 72px from the screen's edge, where Light's own list puts titles.
 */
const val ROW_LEAD_PX = 51

/** How far the middle of a title's letters sits below the middle of its line (measured against the stock Podcasts tool). */
private const val MARK_INK_DROP_PX = 5

/** An action row's icon, centred in the cover's box where a list has covers. */
const val ROW_ACTION_ICON_PX = 57
const val ROW_ACTION_H_PX = 102

/** List padding, so a row's own left edge lines up with Light's music list. */
const val LIST_EDGE_PX = 21

/** TRIAL (40px List Margin): [ROW_LEAD_PX]'s place, 40px from the screen's edge. */
private const val NARROW_ROW_LEAD_PX = 40 - LIST_EDGE_PX

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
    val type = rowType(artless = !covers)
    var nameLines by remember(name) { mutableStateOf(1) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // With pictures the pitch holds and a second line fits inside it.
            // Without, the row grows by the line, keeping the same air between
            // names whether a name takes one line or two.
            .then(if (covers) Modifier.height(px(ROW_H_PX)) else Modifier.heightIn(min = px(type.oneLinePx)))
            .rowClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val titleRisePx = (nameLines - 1) * type.titleLinePx / 2
        DownloadMark(downloaded, onTitleLine = !covers, titleRisePx = titleRisePx)
        if (covers) {
            // Round where an album is square, so the shape says what this is and a
            // face doesn't read as a sleeve that failed to load.
            AppArtwork(
                imageId,
                size = px(ROW_ART_PX),
                corner = px(ROW_ART_PX) / 2,
                fallback = TabGlyphs.Microphone,
            )
            Spacer(Modifier.width(px(ROW_GAP_PX)))
        }
        // A long name takes a second line rather than being cut off.
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = if (covers) px(0) else px((type.oneLinePx - type.titleLinePx) / 2)),
            contentAlignment = Alignment.CenterStart,
        ) {
            RowTitle(name, maxLines = ARTIST_NAME_LINES, type = type) { nameLines = it }
        }
        EndDownloadMark(downloaded, titleRisePx)
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
    /** TRIAL (Tall Album Rows): the list it heads has tall rows, so it takes their pitch and cover box. */
    tallList: Boolean = false,
    onClick: () -> Unit,
) {
    // Read before branching (see DownloadMark).
    val artworkHidden = App.hideListArtwork.collectAsState().value
    val type = rowType(artless = artworkHidden)
    val tall = tallList && headsList && !artworkHidden
    val heightPx = when {
        !headsList -> ROW_ACTION_H_PX
        tall -> TALL_ROW_H_PX
        oneLineList && artworkHidden -> type.oneLinePx
        else -> type.rowPx
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
        Spacer(Modifier.width(px(rowLeadPx())))
        ActionButton(icon, label, inCoverBox = headsList && !artworkHidden, tall = tall)
    }
}

/** The Shuffle row heading a list of covers. */
@Composable
fun ShuffleRow(
    label: String = "Shuffle",
    /** TRIAL (Tall Album Rows): see [PlayAllRow]. */
    tallList: Boolean = false,
    onClick: () -> Unit,
) {
    PlayAllRow(AppIcons.Shuffle, label, headsList = true, tallList = tallList, onClick = onClick)
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
    /** TRIAL (Tall Artist Records): the records under it have tall rows, so it takes their pitch and cover box. */
    tallList: Boolean = false,
) {
    // Read before branching (see DownloadMark).
    val inCoverBox = !App.hideListArtwork.collectAsState().value
    val tall = tallList && inCoverBox
    Row(
        modifier = Modifier.fillMaxWidth().height(px(if (tall) TALL_ROW_H_PX else rowType(artless = !inCoverBox).rowPx)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .width(px(CANVAS_W_PX / 2 - LIST_EDGE_PX))
                .fillMaxHeight()
                .appClickable(onClick = onLeft),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(px(rowLeadPx())))
            ActionButton(leftIcon, leftLabel, inCoverBox, tall = tall)
        }
        if (rightIcon != null && onRight != null) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .appClickable(onClick = onRight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButton(rightIcon, rightLabel, inCoverBox, tall = tall)
            }
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, inCoverBox: Boolean, tall: Boolean = false) {
    if (inCoverBox) {
        Box(Modifier.size(px(if (tall) TALL_ROW_ART_PX else ROW_ART_PX)), contentAlignment = Alignment.Center) {
            AppIcon(icon, size = px(ROW_ACTION_ICON_PX))
        }
    } else {
        AppIcon(icon, size = px(ROW_ACTION_ICON_PX))
    }
    Spacer(Modifier.width(px(ROW_GAP_PX)))
    // Not TextRole.Button: Bold at 0.15em shouts on a full-width row label.
    RowTitle(label, type = rowType(artless = !inCoverBox))
}

/**
 * An album's own tracks at a library list's size: its type and lines, and its
 * 152px row with one line under the title. The number is the title's size too.
 */
private const val NUMBERED_TITLE_PX = ROW_TITLE_PX
private const val NUMBERED_TITLE_LINE_PX = ROW_TITLE_LINE_PX
private const val NUMBERED_SUB_PX = ROW_SUB_PX
private const val NUMBERED_SUB_LINE_PX = ROW_SUB_LINE_PX

/** The row's height with one line under the title, and with two. */
private const val NUMBERED_ROW_H_PX = ROW_H_PX
private const val NUMBERED_ROW_TALL_PX = ROW_H_PX + ROW_SUB_LINE_PX

/** The air a [NumberedRow] with one line under its title leaves above its lines. */
fun numberedRowTopPx(): Int = (NUMBERED_ROW_H_PX - NUMBERED_TITLE_LINE_PX - NUMBERED_SUB_LINE_PX) / 2

/** Air between a number's right edge and the title axis: the gap a list keeps beside a cover. */
private const val NUMBER_GAP_PX = ROW_GAP_PX

/**
 * The number gutter: the album's widest number, from the list's edge where a
 * download mark would sit, then the gap; one width down the whole album, like a
 * printed tracklist's margin, the numbers centred in it and the titles' left
 * edges lined up. Measured once for the album, not per row: the font's digits
 * differ in width, so each number is measured.
 */
@Composable
fun rememberNumberColumn(numbers: List<Int>): Dp {
    val style = appTextStyle(
        pxSp(NUMBERED_TITLE_PX),
        lineHeight = pxSp(NUMBERED_TITLE_LINE_PX),
        role = TextRole.Fine,
        exact = true,
    )
    val measurer = rememberTextMeasurer()
    val widestPx = remember(numbers, style) {
        numbers.map { it.toString() }.distinct()
            .maxOfOrNull { measurer.measure(it, style, softWrap = false).size.width } ?: 0
    }
    val widest = with(LocalDensity.current) { widestPx.toDp() }
    return widest
}

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
    /** The album's widest number: see [rememberNumberColumn]. */
    numberColumn: Dp,
    /**
     * A download mark at the row's right end, on the title's line, in the SDK's
     * grey: on an album page everything but the titles is grey, and a white
     * arrow at the edge would read as a button.
     */
    downloaded: Boolean = false,
) {
    val marks = !App.settings.hideDownloadIcons.state.collectAsState().value
    val numberLead = numberColumn + px(NUMBER_GAP_PX)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(if (subtitle.isNotBlank()) NUMBERED_ROW_TALL_PX else NUMBERED_ROW_H_PX))
            .rowClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // TRIAL (40px List Margin): the numbers start on the lists' margin too.
        if (narrowListMargin()) Spacer(Modifier.width(px(NARROW_ROW_LEAD_PX)))
        if (selected != null) {
            // Select mode swaps the number gutter for the cover and check bubble.
            SelectionArtwork(coverArtId, selected)
            Spacer(Modifier.width(px(ROW_GAP_PX)))
        }
        val lead = if (selected != null) 0.dp else numberLead
        // Lines under the title rather than across the row, so long titles get the
        // whole width. The number reads as the title's, on its baseline, not as
        // the whole block's.
        Column(modifier = Modifier.weight(1f)) {
            Row {
                if (selected == null) {
                    Box(
                        modifier = Modifier
                            .width(lead)
                            .padding(end = px(NUMBER_GAP_PX))
                            .then(if (current) Modifier.align(Alignment.CenterVertically) else Modifier.alignByBaseline()),
                        // Centred in the column, as a printed tracklist sets them.
                        contentAlignment = Alignment.Center,
                    ) {
                        if (current) {
                            AppIcon(AppIcons.Waveform, size = px(41))
                        } else {
                            AppText(
                                number.toString(),
                                pxSp(NUMBERED_TITLE_PX),
                                lineHeight = pxSp(NUMBERED_TITLE_LINE_PX),
                                role = TextRole.Fine,
                                exact = true,
                                dim = true,
                                maxLines = 1,
                                align = TextAlign.Center,
                            )
                        }
                    }
                }
                AppText(
                    title,
                    pxSp(NUMBERED_TITLE_PX),
                    lineHeight = pxSp(NUMBERED_TITLE_LINE_PX),
                    maxLines = 1,
                    exact = true,
                    modifier = Modifier.weight(1f).alignByBaseline(),
                )
                if (downloaded && marks) {
                    Spacer(Modifier.width(px(ROW_GAP_PX)))
                    DownloadGlyph(
                        downloaded,
                        Modifier.align(Alignment.CenterVertically).offset(y = px(MARK_INK_DROP_PX)),
                        grey = true,
                    )
                }
            }
            if (subtitle.isNotBlank()) {
                AppText(
                    subtitle,
                    pxSp(NUMBERED_SUB_PX),
                    lineHeight = pxSp(NUMBERED_SUB_LINE_PX),
                    dim = true,
                    maxLines = 1,
                    exact = true,
                    modifier = Modifier.padding(start = lead),
                )
            }
            AppText(
                detail,
                pxSp(NUMBERED_SUB_PX),
                lineHeight = pxSp(NUMBERED_SUB_LINE_PX),
                dim = true,
                maxLines = 1,
                exact = true,
                modifier = Modifier.padding(start = lead),
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
