package com.sublunar.amp.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.sublunar.amp.ui.DISABLED_ALPHA
import com.sublunar.amp.ui.LocalDimTextAlpha
import com.sublunar.amp.ui.LocalGreyText
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.ui.LightProgressBar
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToSp
import kotlin.math.abs

/** The icons Amp draws, named for what they stand for. Glyphs live in MaterialGlyphs.kt. */
object AppIcons {
    val Album = Icons.Filled.Album

    /**
     * The Albums tab: two offset square sleeves. A disc belongs to a playing track,
     * a stack is what the tab holds.
     */
    val AlbumStack = Icons.Filled.FilterNone
    val MusicNote = Icons.Filled.MusicNote
    val MusicNoteOutline = Icons.Outlined.MusicNote

    /**
     * Artists: a filled mic, at the same weight as the note and the disc beside it.
     * LightOS's own is a thin outline and reads as a hole in the row.
     */
    val QueueMusic = Icons.AutoMirrored.Filled.QueueMusic
    val MoreVert = Icons.Filled.MoreVert

    /** LightOS's own waveform, see [WaveformSymbol]. */
    val Waveform = WaveformSymbol
    val Sort = Icons.AutoMirrored.Filled.Sort
    val Favorite = Icons.Filled.Favorite
    val FavoriteBorder = Icons.Filled.FavoriteBorder
    val Star = Icons.Filled.Star
    val StarBorder = Icons.Filled.StarBorder
    val Shuffle = Icons.Filled.Shuffle
    val PlayArrow = Icons.Filled.PlayArrow
    val Pause = Icons.Filled.Pause
    val FastRewind = Icons.Filled.FastRewind
    val FastForward = Icons.Filled.FastForward
    val Repeat = Icons.Filled.Repeat
    val RepeatOne = Icons.Filled.RepeatOne
    val Add = Icons.Filled.Add

    /** The same plus, ringed: what the player shows for a liked track. */
    val AddCircle = Icons.Filled.AddCircleOutline

    /** Selection state in multi-select mode: filled when picked, ring when not. */
    val Selected = Icons.Filled.CheckCircle
    val Unselected = Icons.Filled.RadioButtonUnchecked
    val Close = Icons.Filled.Close
    val Search = Icons.Filled.Search
    val Settings = Icons.Filled.Settings
    val Dehaze = Icons.Filled.Dehaze
    val FormatListBulleted = Icons.AutoMirrored.Filled.FormatListBulleted
    val VolumeUp = Icons.AutoMirrored.Filled.VolumeUp
    val VolumeDown = Icons.AutoMirrored.Filled.VolumeDown
    val Bluetooth = Icons.Filled.Bluetooth
    val Smartphone = Icons.Filled.Smartphone
    val Speaker = Icons.Filled.Speaker
    val Dns = Icons.Filled.Dns
    val DeleteOutline = Icons.Filled.DeleteOutline
    val ArrowUpward = Icons.Filled.ArrowUpward
    val ArrowDownward = Icons.Filled.ArrowDownward
    val Whatshot = Icons.Filled.Whatshot
    val Cast = Icons.Filled.Cast
    val Refresh = Icons.Filled.Refresh

    /** Light marks a downloaded row with the same arrow it downloads with. */
    val CloudOff = Icons.Filled.CloudOff
}

@Composable
fun AppIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = px(71),
    tint: Color = LightThemeTokens.colors.content,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size),
    )
}

/**
 * Which of the LP3's type styles a string belongs to. Amp sets its own sizes (tuned
 * to this screen's grid), the role supplies Light's weight and tracking.
 */
enum class TextRole { Heading, Subheading, Copy, Button, Detail, Fine }

@Composable
private fun TextRole.style(): TextStyle {
    val t = LightThemeTokens.typography
    return when (this) {
        TextRole.Heading -> t.heading
        TextRole.Subheading -> t.subheading
        TextRole.Copy -> t.copy
        TextRole.Button -> t.button
        TextRole.Detail -> t.detail
        TextRole.Fine -> t.fine
    }
}

/**
 * A role's tracking, re-expressed for the size actually being drawn. The SDK states
 * letter spacing in absolute sp against its own font size (button: 4.5sp at 30sp,
 * 0.15em). Copied onto a 15sp label that would be 0.3em, so it scales as a ratio.
 */
private fun trackingFor(style: TextStyle, size: TextUnit): TextUnit {
    val spacing = style.letterSpacing
    val base = style.fontSize
    if (!spacing.isSp || !base.isSp || base.value <= 0f || spacing.value == 0f) return 0.sp
    return size * (spacing.value / base.value)
}

/**
 * The weights the LP3 ships AkkuratLLTT in. There is no Medium: a 500 request
 * resolves to the nearest face (Regular) and Compose only synthesises bolder from
 * 600, so it would draw as body copy. [onDeviceWeight] rounds up instead, so "a bit
 * heavier" really renders heavier.
 */
private val DEVICE_WEIGHTS = listOf(
    FontWeight.Thin,
    FontWeight.Light,
    FontWeight.Normal,
    FontWeight.Bold,
    FontWeight.Black,
)

private fun onDeviceWeight(requested: FontWeight): FontWeight =
    DEVICE_WEIGHTS.firstOrNull { it.weight >= requested.weight } ?: FontWeight.Black

/**
 * The style [AppText] draws with, exposed so a fit decision (the header's title)
 * measures the string in exactly the face, size, weight and tracking it will be
 * drawn in.
 */
@Composable
fun appTextStyle(
    size: TextUnit,
    lineHeight: TextUnit = TextUnit.Unspecified,
    role: TextRole = TextRole.Copy,
    weight: FontWeight? = null,
    align: TextAlign? = null,
    /** Drawn at exactly the size asked, not snapped to the nearest SDK style. */
    exact: Boolean = false,
): TextStyle {
    if (!exact) return nearestSdkStyle(size, role, weight, align)
    val base = role.style()
    return base.copy(
        fontSize = size,
        lineHeight = if (lineHeight != TextUnit.Unspecified) lineHeight else size * 1.25f,
        fontWeight = onDeviceWeight(weight ?: base.fontWeight ?: FontWeight.Normal),
        letterSpacing = trackingFor(base, size),
        textAlign = align ?: TextAlign.Unspecified,
    )
}

/**
 * The SDK style nearest [size], whole, as the SDK
 * draws it: its size, line height, tracking and weight (unless [weight] is
 * given), scaled to the screen's height. Between styles of the same size the
 * text's own [role] wins, else the first listed.
 */
@Composable
private fun nearestSdkStyle(size: TextUnit, role: TextRole, weight: FontWeight?, align: TextAlign?): TextStyle {
    val t = LightThemeTokens.typography
    val own = role.style()
    val styles = listOf(
        t.title, t.subtitle, t.heading, t.copy, t.subheading, t.button,
        t.paragraph, t.fine, t.paragraphWide, t.detail, t.superfine, t.micro,
    )
    val sized = styles.map { it to it.fontSize.value.designVerticalPxToSp() }
    val nearest = sized.minOf { abs(it.second.value - size.value) }
    val ties = sized.filter { abs(it.second.value - size.value) - nearest < 0.01f }
    val (style, fontSize) = ties.firstOrNull { it.first == own } ?: ties.first()
    val spacing = style.letterSpacing
    return style.copy(
        fontSize = fontSize,
        lineHeight = style.lineHeight.value.designVerticalPxToSp(),
        letterSpacing = if (spacing.isSp) spacing.value.designVerticalPxToSp() else TextUnit.Unspecified,
        // A weight the caller asked for outright stands (Light's button, kept Regular).
        fontWeight = onDeviceWeight(weight ?: style.fontWeight ?: FontWeight.Normal),
        textAlign = align ?: TextAlign.Unspecified,
    )
}

/** Text in the LP3 font at an exact size. [dim] draws it grey, for secondary lines: see [LocalDimTextAlpha]. */
@Composable
fun AppText(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    lineHeight: TextUnit = TextUnit.Unspecified,
    color: Color = LightThemeTokens.colors.content,
    dim: Boolean = false,
    role: TextRole = TextRole.Copy,
    /** Overrides the role's weight when a specific one is wanted. */
    weight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    /** Drawn at exactly the size asked, not snapped to the nearest SDK style. */
    exact: Boolean = false,
    /** How the text came out (its line count), for a neighbour that makes room. */
    onTextLayout: (TextLayoutResult) -> Unit = {},
    /** Drawn faded as unavailable: see [DISABLED_ALPHA]. Takes the place of [dim]. */
    disabled: Boolean = false,
    /** A line height kept even where the text otherwise takes an SDK style whole (not [exact]). */
    lineHeightOverride: TextUnit = TextUnit.Unspecified,
) {
    val style = appTextStyle(size, lineHeight, role, weight, align, exact)
        .let { if (lineHeightOverride != TextUnit.Unspecified) it.copy(lineHeight = lineHeightOverride) else it }
    val greyColour = dim && !disabled && LocalGreyText.current
    Text(
        text = text,
        modifier = when {
            disabled -> modifier.alpha(DISABLED_ALPHA)
            dim && !greyColour -> modifier.alpha(LocalDimTextAlpha.current)
            else -> modifier
        },
        color = if (greyColour) LightThemeTokens.colors.contentSecondary else color,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = onTextLayout,
    )
}

/**
 * The stock LP3 fader (hairline track, thick fill, no knob) for seeking and volume.
 * Only the drawing comes from the SDK: each caller handles touch itself, because it
 * needs a scrub preview or a release-only commit `LightTouchableProgressBar` lacks.
 */
@Composable
fun AppProgressBar(progress: Float) {
    LightProgressBar(LightThemeTokens.colors, progress)
}
