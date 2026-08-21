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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sublunar.amp.App
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.ui.n
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.ui.LightThemeTokens

@Composable
fun AppArtwork(
    coverArtId: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = n(4),
    fallback: ImageVector = AppIcons.MusicNote,
) {
    // Not even the placeholder tile: a column of identical glyphs is the thing
    // the setting is trying to be rid of.
    if (App.hideArtwork.collectAsState().value) return
    val px = with(LocalDensity.current) { size.roundToPx() }
    val image = rememberArtwork(coverArtId, px)
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

/**
 * A list row in the Light music app's proportions: title at [ROW_TITLE_PX], a
 * dimmer subtitle at [ROW_SUB_PX] under it, on the 160px pitch its own lists use.
 *
 * The download mark is a small glyph immediately left of the title, where Light
 * puts it, rather than a badge at the far end of the row — at the far end it read
 * as a control rather than as a property of the track.
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
    /** Shows the offline badge — set for a downloaded song or a complete album. */
    downloaded: Boolean = false,
    /**
     * Selection state, or null when the list isn't in multi-select mode.
     *
     * The circle takes the artwork's place rather than sitting beside it: the row
     * is 160px with no room to spare, and swapping keeps every title on the same
     * left edge whether or not selection is active.
     */
    selected: Boolean? = null,
) {
    val artworkHidden = App.hideArtwork.collectAsState().value
    // Read here rather than inside the branch below: behind an `if` this stops
    // subscribing and the marks freeze as they were — the trap the liked
    // switches document.
    val marks = !App.hideDownloadIcons.collectAsState().value
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(px(ROW_H_PX))
            .rowClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The download mark leads the row, in a slot of its own. Everything after
        // it starts on the same axis whether or not the row is downloaded — and
        // with covers switched off, that axis is where Light puts its titles.
        // The slot stays whether or not anything is in it: it is what keeps
        // every list's titles on one axis, and it already spends most of its
        // life empty on rows that aren't downloaded.
        Box(modifier = Modifier.width(px(ROW_LEAD_PX))) {
            if (downloaded && marks) AppIcon(AppIcons.Downloaded, size = px(ROW_MARK_PX))
        }
        if (selected != null) {
            Box(
                modifier = Modifier.size(px(ROW_ART_PX)),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(
                    if (selected) AppIcons.Selected else AppIcons.Unselected,
                    size = n(26),
                    tint = if (selected) {
                        LightThemeTokens.colors.content
                    } else {
                        LightThemeTokens.colors.contentSecondary
                    },
                )
            }
            Spacer(Modifier.width(px(ROW_GAP_PX)))
        } else if (!artworkHidden) {
            AppArtwork(coverArtId = coverArtId, size = px(ROW_ART_PX), fallback = fallback)
            // Only when there is artwork to sit beside: with none, the title
            // belongs against the mark, not floating a cover's gap away from it.
            Spacer(Modifier.width(px(ROW_GAP_PX)))
        }
        Column(modifier = Modifier.weight(1f)) {
            AppText(
                title,
                pxSp(ROW_TITLE_PX),
                lineHeight = pxSp(ROW_TITLE_LINE_PX),
                maxLines = 1,
            )
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
    }
}

/**
 * List metrics taken from the Light music app, in physical pixels and multiples
 * of 3 so each is a whole dp as well.
 *
 * Sized off its screenshot rather than guessed: at a 163px row pitch the title's
 * caps measure ~43px and the subtitle's ~29px, which at Akkurat's cap height
 * (~0.72 em) puts the two at 60px and 42px. Shared with the queue and the artist
 * list so every row in the app is the same piece of typography.
 */
// Previous settings, kept for a quick revert: row 160, title 60/72, sub 42/51.
const val ROW_H_PX = 150

/** A row carrying one line only — see [ArtistRow] with its counts switched off. */
const val ROW_SINGLE_H_PX = 108
const val ROW_TITLE_PX = 54
const val ROW_TITLE_LINE_PX = 66
const val ROW_SUB_PX = 39
const val ROW_SUB_LINE_PX = 48

/** Cover size, and the gap beside it — the same 32px that separates two covers. */
const val ROW_ART_PX = 128
const val ROW_GAP_PX = 32

/**
 * The leading slot the download mark sits in.
 *
 * Sized so that what follows starts 72px from the screen's edge, which is where
 * Light's own list puts its titles: [LIST_EDGE_PX] of list padding plus this.
 */
const val ROW_LEAD_PX = 51

private const val ROW_MARK_PX = 33
/**
 * Smaller than a list row's own furniture, and closer to its label.
 *
 * Both are pulled in so the longest label ("Liked Albums") fits beside its
 * neighbour without truncating; at the list's usual 66/32 it was over by about
 * two characters.
 */
const val ROW_ACTION_ICON_PX = 57
private const val ACTION_GAP_PX = 24
const val ROW_ACTION_H_PX = 102

/** The LP3's panel, in physical pixels — the axis these rows are placed on. */
private const val SCREEN_W_PX = 1080

/**
 * Where the left of a pair centres, with the right mirrored across the screen.
 *
 * Three tenths in: the two sit a fifth of the width either side of the middle,
 * which is close enough to read as one control with two halves. "Liked Albums"
 * fits because [ROW_ACTION_ICON_PX] and [ACTION_GAP_PX] are trimmed for these
 * rows — it was over by about two characters at the list's usual spacing.
 */
private const val SPLIT_LEFT_X_PX = SCREEN_W_PX * 3 / 10

/** Wide enough for "Shuffle", narrow enough that the two slots don't meet. */
/**
 * As wide as a pair can be without overlapping: half the distance between the
 * two centres, each side. "Liked Albums" needs all but a few pixels of it.
 */
private const val ACTION_SLOT_PX = SCREEN_W_PX - SPLIT_LEFT_X_PX * 2

/**
 * A button with no neighbour can have the room a long label wants — as much as
 * fits between the screen's edge and twice its own centre.
 */
private const val SOLO_SLOT_PX = 600

/** List padding, so a row's own left edge lines up with the reference's. */
const val LIST_EDGE_PX = 21

/**
 * Top padding, set so the action row at the head of a library list sits evenly
 * between the header above it and the first cover below.
 *
 * Measured rather than derived: the gap below "Play" is the row's own slack plus
 * the 11px a 128px cover leaves inside a 150px row, and this is what makes the
 * gap above match it.
 */
const val LIST_TOP_PX = 15

/**
 * An artist row: the same metrics as a track row, minus the cover an artist
 * hasn't got — but keeping the leading slot, so names start on the axis every
 * other list's titles start on.
 */
@Composable
fun ArtistRow(
    name: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** Shows the offline badge — set when every track of the artist's is downloaded. */
    downloaded: Boolean = false,
    /** The server's picture of them, where it has one — see Artist.imageId. */
    imageId: String? = null,
) {
    // With pictures off the row is a line of text again, at the tighter pitch it
    // has always had; a picture needs the taller one the track rows use. Artists
    // can lose theirs on their own, without the sleeves going with them.
    // Read unconditionally, both of them — see the note in TrackRow.
    val wantsCovers = !App.hideArtistImages.collectAsState().value
    val local = App.source.collectAsState().value.kind == SourceKind.LOCAL
    // The phone's own library has no artist pictures to fetch — nothing keeps
    // them and no server can be asked — so the slot would be a column of empty
    // circles. Text at the tighter pitch says the same thing in less room.
    val covers = wantsCovers && !local
    // Read unconditionally — see the note in TrackRow.
    val marks = !App.hideDownloadIcons.collectAsState().value
    Row(
        modifier = modifier
            .fillMaxWidth()
            // One line, so the rows close up rather than leaving a gap where a
            // second one would have been.
            .height(px(if (covers) ROW_H_PX else ROW_SINGLE_H_PX))
            .rowClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The mark's own slot, which is also what keeps a name on the same axis
        // as the titles in every other list.
        Box(modifier = Modifier.width(px(ROW_LEAD_PX))) {
            if (downloaded && marks) AppIcon(AppIcons.Downloaded, size = px(ROW_MARK_PX))
        }
        if (covers) {
            // Round, where an album is square: the shape says which kind of
            // thing this is before the name is read, and a face in a square
            // reads as a sleeve that failed to load.
            AppArtwork(
                imageId,
                size = px(ROW_ART_PX),
                corner = px(ROW_ART_PX) / 2,
                fallback = AppIcons.RecordVoiceOver,
            )
            Spacer(Modifier.width(px(ROW_GAP_PX)))
        }
        Column {
            AppText(
                name,
                pxSp(ROW_TITLE_PX),
                lineHeight = pxSp(ROW_TITLE_LINE_PX),
                maxLines = 1,
            )
        }
    }
}

/** Icon + label action row (Liked, Shuffle, Play All). */
@Composable
fun PlayAllRow(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Shorter than a track row on purpose: these carry one line, and at
            // the full 160px pitch a stack of them pushed the list itself off the
            // screen.
            .heightIn(min = px(ROW_ACTION_H_PX))
            .appClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon and label both start where the row's content starts — the covers'
        // left edge, which is also where titles start when covers are off. An
        // action row is a line of the list, not a row with an empty sleeve.
        Spacer(Modifier.width(px(ROW_LEAD_PX)))
        AppIcon(icon, size = px(ROW_ACTION_ICON_PX))
        Spacer(Modifier.width(px(ROW_GAP_PX)))
        // Not TextRole.Button: the LP3's button style is Bold at 0.15em, which on a
        // full-width row label reads as shouty rather than as Light's restraint.
        AppText(label, pxSp(ROW_TITLE_PX), lineHeight = pxSp(ROW_TITLE_LINE_PX), maxLines = 1)
    }
}

/**
 * Two action buttons side by side, each centred in its own half.
 *
 * Play and Shuffle are the same kind of thing done two ways, so they get equal
 * columns instead of one sitting above the other — which also keeps the list
 * itself two rows further up the screen.
 */
@Composable
fun SplitActionRow(
    leftIcon: ImageVector? = null,
    leftLabel: String = "",
    onLeft: (() -> Unit)? = null,
    /**
     * The right-hand button, or nothing.
     *
     * Null leaves the left one exactly where it is rather than centring it: a
     * page whose second button arrives a moment later (an artist's popular
     * tracks, fetched) would otherwise start centred and jump sideways.
     */
    rightIcon: ImageVector? = null,
    rightLabel: String = "",
    onRight: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth().heightIn(min = px(ROW_ACTION_H_PX)),
    ) {
        val left = if (leftIcon != null && onLeft != null) leftIcon to onLeft else null
        val right = if (rightIcon != null && onRight != null) rightIcon to onRight else null
        when {
            left != null && right != null -> {
                ActionSlot(SPLIT_LEFT_X_PX, left.first, leftLabel, left.second)
                ActionSlot(SCREEN_W_PX - SPLIT_LEFT_X_PX, right.first, rightLabel, right.second)
            }
            // A lone button keeps the left slot rather than moving to the
            // middle: every one of these rows then starts on the same axis,
            // whether or not it has a second half.
            left != null ->
                ActionSlot(SPLIT_LEFT_X_PX, left.first, leftLabel, left.second, SOLO_SLOT_PX)
            right != null ->
                ActionSlot(SPLIT_LEFT_X_PX, right.first, rightLabel, right.second, SOLO_SLOT_PX)
        }
    }
}

/**
 * One action button, centred on an absolute position across the screen.
 *
 * Positioned rather than laid out in a weighted Row, because the row lives
 * inside a list whose padding depends on what is down its right edge: the A–Z
 * strip is twice the width of the scroll bar, and the same two buttons landed
 * 16px apart on the albums list and the liked list. [centreX] is measured from
 * the screen's own left edge, so it is the same everywhere; [LIST_EDGE_PX] is
 * subtracted because that is where every list's content begins.
 *
 * The slot is kept narrow enough to stay inside the padded box on the widest of
 * them, so its tap target isn't clipped by the parent's bounds.
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
        ActionContent(icon, label)
    }
}

@Composable
private fun ActionContent(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icon, size = px(ROW_ACTION_ICON_PX))
        Spacer(Modifier.width(px(ACTION_GAP_PX)))
        AppText(label, pxSp(ROW_TITLE_PX), lineHeight = pxSp(ROW_TITLE_LINE_PX), maxLines = 1)
    }
}

/**
 * An album's own tracks, set a notch smaller than a library list.
 *
 * The page has already said which record this is, in the card at the top — the
 * track names are a running order rather than a list to browse, and at the
 * library's size they shouted over it.
 */
private const val NUMBERED_TITLE_PX = 48
private const val NUMBERED_TITLE_LINE_PX = 60
private const val NUMBERED_SUB_PX = 36
private const val NUMBERED_SUB_LINE_PX = 45
/**
 * Two lines under the title where a record gives one — who plays on the song,
 * then how long it runs — so the row is as tall as the type in it.
 */
private const val NUMBERED_ROW_H_PX = 132
private const val NUMBERED_ROW_TALL_PX = 177

/** Track number (or now-playing icon when current) + title + a line under it. */
@Composable
fun NumberedRow(
    number: Int?,
    title: String,
    /** The line under the title — on a record, who plays on the song. */
    subtitle: String,
    /** A second line under that, where there is one. */
    detail: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    /** Selection state, or null when the list isn't in multi-select mode. */
    selected: Boolean? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(px(if (detail == null) NUMBERED_ROW_H_PX else NUMBERED_ROW_TALL_PX))
            .rowClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The circle stands in for the track number, keeping titles aligned.
        Box(modifier = Modifier.width(n(30)), contentAlignment = Alignment.Center) {
            when {
                selected != null -> AppIcon(
                    if (selected) AppIcons.Selected else AppIcons.Unselected,
                    size = n(20),
                    tint = if (selected) {
                        LightThemeTokens.colors.content
                    } else {
                        LightThemeTokens.colors.contentSecondary
                    },
                )
                current -> AppIcon(AppIcons.Waveform, size = n(16))
                else -> AppText(
                    number?.toString() ?: "",
                    pxSp(ROW_SUB_PX),
                    lineHeight = pxSp(ROW_SUB_LINE_PX),
                    role = TextRole.Detail,
                    dim = true,
                    align = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.width(px(ROW_GAP_PX)))
        // Under the title rather than across the row, which leaves long titles
        // the whole width instead of a column of them truncating short of a
        // right-hand gutter. On a record this line is the performer: a track's
        // own credit is the thing that varies down an album's list — the guests
        // on one song and not the next — where its length is a number you have
        // to read to learn nothing.
        Column(modifier = Modifier.weight(1f)) {
            AppText(
                title,
                pxSp(NUMBERED_TITLE_PX),
                lineHeight = pxSp(NUMBERED_TITLE_LINE_PX),
                maxLines = 1,
            )
            AppText(
                subtitle,
                pxSp(NUMBERED_SUB_PX),
                lineHeight = pxSp(NUMBERED_SUB_LINE_PX),
                dim = true,
                maxLines = 1,
            )
            if (detail != null) {
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
}

/**
 * Padding shared by every list in the app.
 *
 * [LIST_EDGE_PX] puts a row's own left edge where the Light music app puts its
 * download mark; the row's leading slot then carries everything after it to the
 * 72px axis its titles sit on.
 */
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
 * A list with a scroll bar down its right edge.
 *
 * Everything but the tab lists uses this: they have the A–Z strip instead, which
 * carries its own position marker. Content is inset by the bar's lane so a long
 * title never runs underneath it.
 */
@Composable
fun ScrollableList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Box(modifier = modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = listPadding(end = px(SCROLLBAR_LANE_PX)),
            content = content,
        )
        ListScrollBar(state)
    }
}

/**
 * A library list that remembers where it was.
 *
 * Thin, but named: every library page wants the same two things — an anchor so a
 * trip to another screen comes back to the same row, and a bar down the right
 * edge unless the page is one of the few that never had one.
 */
@Composable
fun LibraryList(
    anchor: String,
    modifier: Modifier = Modifier,
    headerCount: Int = 0,
    /** The right-hand lane and its bar, as the few lists that never had one. */
    scrollBar: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    val state = rememberListAnchor(anchor, headerCount)
    Box(modifier = modifier) {
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
