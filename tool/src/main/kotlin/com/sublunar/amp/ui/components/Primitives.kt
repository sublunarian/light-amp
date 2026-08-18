package com.sublunar.amp.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.sublunar.amp.ui.n
import com.thelightphone.sdk.ui.LightProgressBar
import com.thelightphone.sdk.ui.LightThemeTokens

/** Material icons matching the original app, referenced by name. */
object AppIcons {
    val Album = Icons.Filled.Album

    /**
     * The Albums tab: two square sleeves, one behind the other.
     *
     * A disc belongs to a track that is playing; a *stack* is what the tab holds.
     * FilterNone is Material's plain pair of offset squares — the same shape
     * Apple uses for its own Albums list, and the only geometric one in the set.
     */
    val AlbumStack = Icons.Filled.FilterNone
    val MusicNote = Icons.Filled.MusicNote
    /**
     * A filled mic, to sit at the same weight as the note and the disc beside it
     * — LightOS's own is a thin outline and read as a hole in the row.
     */
    val RecordVoiceOver = Icons.Filled.Mic
    val QueueMusic = Icons.AutoMirrored.Filled.QueueMusic
    val PlaylistPlay = Icons.AutoMirrored.Filled.PlaylistPlay
    val MoreHoriz = Icons.Filled.MoreHoriz
    val MoreVert = Icons.Filled.MoreVert
    val GraphicEq = Icons.Filled.GraphicEq

    /** LightOS's own waveform; see [WaveformSymbol]. */
    val Waveform = WaveformSymbol
    val Lyrics = Icons.Filled.Lyrics

    /**
     * Leaving Now Playing goes back to the library, so the button says so — and
     * the simplified bar's middle button, which is that same library.
     */
    val LibraryMusic = Icons.Filled.LibraryMusic

    /** Genres, on the library index: a tag is what a genre is. */
    val Genre = Icons.Filled.LocalOffer

    /** Composers, on the library index — written, rather than performed. */
    val Composer = Icons.Filled.HistoryEdu
    val Sort = Icons.AutoMirrored.Filled.Sort
    val Favorite = Icons.Filled.Favorite
    val FavoriteBorder = Icons.Filled.FavoriteBorder
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
    val ArrowBackIos = Icons.AutoMirrored.Filled.ArrowBackIos

    /** Marks a header title that opens a menu. */
    val ArrowDropDown = Icons.Filled.ArrowDropDown
    val Close = Icons.Filled.Close
    val Search = Icons.Filled.Search
    val Settings = Icons.Filled.Settings
    val Dehaze = Icons.Filled.Dehaze
    val FormatListBulleted = Icons.AutoMirrored.Filled.FormatListBulleted
    val VolumeUp = Icons.AutoMirrored.Filled.VolumeUp

    /** Output routing — a speaker cabinet, not a volume level. */
    val Speaker = Icons.Filled.Speaker
    val VolumeDown = Icons.AutoMirrored.Filled.VolumeDown
    val Bluetooth = Icons.Filled.Bluetooth
    val Smartphone = Icons.Filled.Smartphone
    val DeleteOutline = Icons.Filled.DeleteOutline
    val ArrowUpward = Icons.Filled.ArrowUpward
    val ArrowDownward = Icons.Filled.ArrowDownward
    val Whatshot = Icons.Filled.Whatshot
    val Cast = Icons.Filled.Cast
    val Refresh = Icons.Filled.Refresh
    val Download = Icons.Filled.Download
    val DownloadDone = Icons.Filled.DownloadDone

    /** Light marks a downloaded row with the same arrow it downloads with. */
    val Downloaded = Icons.Filled.Download
    val CloudOff = Icons.Filled.CloudOff
}

@Composable
fun AppIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = n(28),
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
 * Text in the LP3 font at an exact size. [dim] applies the original app's 0.5
 * opacity used for secondary lines (artist, subtitle).
 */
/**
 * Which of the LP3's type styles a string belongs to.
 *
 * The app sets its own sizes — those are tuned to this screen's grid — but weight
 * and tracking now come from the Light design language instead of being forced to
 * Regular/0 everywhere, which flattened the whole scale into one style.
 */
enum class TextRole { Title, Heading, Subheading, Copy, Button, Detail, Fine }

@Composable
private fun TextRole.style(): TextStyle {
    val t = LightThemeTokens.typography
    return when (this) {
        TextRole.Title -> t.title
        TextRole.Heading -> t.heading
        TextRole.Subheading -> t.subheading
        TextRole.Copy -> t.copy
        TextRole.Button -> t.button
        TextRole.Detail -> t.detail
        TextRole.Fine -> t.fine
    }
}

/**
 * A role's tracking, re-expressed for the size actually being drawn.
 *
 * The SDK states letter spacing in absolute sp against its own font size — button
 * is 4.5sp at 30sp, i.e. 0.15em. Copying that number onto a 15sp label would give
 * 0.3em and look broken, so convert to a ratio and scale it.
 */
private fun trackingFor(style: TextStyle, size: TextUnit): TextUnit {
    val spacing = style.letterSpacing
    val base = style.fontSize
    if (!spacing.isSp || !base.isSp || base.value <= 0f || spacing.value == 0f) return 0.sp
    return size * (spacing.value / base.value)
}

/**
 * Snap a requested weight to a face the LP3 actually ships.
 *
 * `/system/fonts` has AkkuratLLTT in Thin (100), Light (300), Regular (400),
 * Bold (700) and Black (900), plus italics — **but no Medium**. Asking for
 * `FontWeight.Medium` (500) is therefore silently a no-op: Android resolves it to
 * the nearest face, which is Regular, and Compose only synthesises a heavier face
 * from 600 up. The text comes out identical to body copy.
 *
 * So round *up* to the next face that exists rather than to the nearest one. "A bit
 * heavier" then actually renders heavier, instead of the request evaporating.
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
 * The style [AppText] draws with, exposed so it can also be *measured* with.
 *
 * Anything deciding whether a string fits has to lay it out in the same face,
 * size, weight and tracking it will be drawn in — a decision made against a
 * near-enough style is a decision about a different string. See the header's
 * fitted title.
 */
@Composable
fun appTextStyle(
    size: TextUnit,
    lineHeight: TextUnit = TextUnit.Unspecified,
    role: TextRole = TextRole.Copy,
    weight: FontWeight? = null,
    align: TextAlign? = null,
): androidx.compose.ui.text.TextStyle {
    val base = role.style()
    return base.copy(
        fontSize = size,
        lineHeight = if (lineHeight != TextUnit.Unspecified) lineHeight else size * 1.25f,
        fontWeight = onDeviceWeight(weight ?: base.fontWeight ?: FontWeight.Normal),
        letterSpacing = trackingFor(base, size),
        textAlign = align ?: TextAlign.Unspecified,
    )
}

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
) {
    val style = appTextStyle(size, lineHeight, role, weight, align)
    Text(
        text = text,
        modifier = if (dim) modifier.alpha(0.5f) else modifier,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
    )
}

/**
 * The stock LP3 fader: a hairline track with a thick fill and no knob, used for
 * seeking and for volume alike — the OS draws both the same way.
 *
 * Delegates to the SDK's own [LightProgressBar] rather than restating its
 * proportions (0.1 and 0.5 grid units), so the app follows the system if they
 * ever change. Only the drawing comes from the SDK: the touch handling stays
 * with each caller, which needs a scrub preview or a release-only commit that
 * `LightTouchableProgressBar` doesn't offer.
 */
@Composable
fun AppProgressBar(progress: Float) {
    LightProgressBar(LightThemeTokens.colors, progress)
}
