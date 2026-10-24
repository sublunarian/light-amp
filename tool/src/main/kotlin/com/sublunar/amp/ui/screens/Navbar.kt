package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.sublunar.amp.App
import com.sublunar.amp.data.LayoutMode
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.appClickable
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * The bottom bar, in whichever shape the Layout setting asks for. Both shapes
 * take the same handlers and each uses what it needs, so a caller doesn't have
 * to know which one is showing.
 */
@Composable
fun Navbar(
    current: LibraryTab?,
    /**
     * Every tab goes through here, never LibraryNav directly: from a sub-page,
     * choosing a tab has to unwind the stack as well, and only the caller knows.
     */
    onSelect: (LibraryTab) -> Unit,
    onSearch: () -> Unit,
    searchActive: Boolean = false,
    onNowPlaying: () -> Unit,
    /** Simplified's library button — see [LibraryNav.libraryIndex]. */
    onBrowse: () -> Unit,
) {
    // The eager state rather than the preference Flow: the bar is on every
    // screen, and a fresh collect draws a frame of the wrong bar.
    val simplified = App.settings.layoutMode.state.collectAsState().value == LayoutMode.SIMPLIFIED
    if (simplified) {
        SimplifiedNavbar(onSearch, onNowPlaying, onBrowse, searchActive)
    } else {
        StandardNavbar(current, onSelect, onNowPlaying)
    }
}

/** Simplified layout: the library, search and the player, as the phone's own three buttons. */
@Composable
private fun SimplifiedNavbar(
    onSearch: () -> Unit,
    onNowPlaying: () -> Unit,
    onBrowse: () -> Unit,
    searchActive: Boolean,
) {
    val artworkEdge = px(EDGE_GLYPH_PX)
    // The end glyphs' outer edges line up with that edge. They are centred in
    // boxes of the bar's own height, so the padding is that distance less the
    // slack the glyph has inside its box, which differs with the glyph's size.
    val tile = px(BOTTOM_BAR_PX)
    val librarySlack = (tile - px(NAV_ICON_PX) * navScale(LIBRARY_DRAWN_PX, TALL_TARGET_PX)) / 2
    val playerSlack = (tile - px(NAV_ICON_PX) * NOW_PLAYING_SCALE) / 2
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(BOTTOM_BAR_PX))
            .padding(
                start = (artworkEdge - librarySlack).coerceAtLeast(0.dp),
                end = (artworkEdge - playerSlack).coerceAtLeast(0.dp),
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Exactly one of the two browsing buttons is lit. The library covers the
        // index and everything reached from it. The player hides the bar, so it
        // is never the lit one.
        NavTile(
            AppIcons.LibraryMusic,
            active = !searchActive,
            scale = navScale(LIBRARY_DRAWN_PX, TALL_TARGET_PX),
            box = BOTTOM_BAR_PX,
        ) { onBrowse() }
        Spacer(Modifier.weight(1f))
        NavTile(AppIcons.Search, active = searchActive) { onSearch() }
        Spacer(Modifier.weight(1f))
        NavTile(
            AppIcons.Waveform,
            active = false,
            scale = NOW_PLAYING_SCALE,
            box = BOTTOM_BAR_PX,
        ) { onNowPlaying() }
    }
}

/** Standard layout: the four tabs across the bar, then the player. */
@Composable
private fun StandardNavbar(
    current: LibraryTab?,
    onSelect: (LibraryTab) -> Unit,
    onNowPlaying: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(BOTTOM_BAR_PX)),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavIcon(
            AppIcons.QueueMusic,
            current == LibraryTab.PLAYLISTS,
            scale = PLAYLISTS_SCALE,
            lift = PLAYLISTS_LIFT_PX,
        ) { onSelect(LibraryTab.PLAYLISTS) }
        NavIcon(
            AppIcons.RecordVoiceOver,
            current == LibraryTab.ARTISTS,
            scale = navScale(ARTISTS_DRAWN_PX, ARTISTS_TARGET_PX),
        ) { onSelect(LibraryTab.ARTISTS) }
        NavIcon(
            AppIcons.AlbumStack,
            current == LibraryTab.ALBUMS,
            scale = navScale(ALBUMS_DRAWN_PX, TALL_TARGET_PX),
        ) { onSelect(LibraryTab.ALBUMS) }
        NavIcon(
            AppIcons.MusicNote,
            current == LibraryTab.SONGS,
            scale = navScale(SONGS_DRAWN_PX, TALL_TARGET_PX),
        ) { onSelect(LibraryTab.SONGS) }
        // The player after the four ways of looking at the library: the one
        // thing here you arrive at rather than browse to. Never lit, since it
        // hides this bar.
        NavIcon(AppIcons.Waveform, active = false, scale = NOW_PLAYING_SCALE) { onNowPlaying() }
    }
}

@Composable
private fun NavIcon(
    icon: ImageVector,
    /** The tab you are on is the bright one; the rest step back. */
    active: Boolean,
    /** Trims a glyph that draws larger than the rest at the same box size. */
    scale: Float = 1f,
    /** Nudges the glyph up inside its box. */
    lift: Int = 0,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(px(NAV_BOX_PX))
            .appClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            icon = icon,
            size = px(NAV_ICON_PX) * scale,
            tint = LightThemeTokens.colors.content,
            modifier = (if (active) Modifier else Modifier.alpha(NAV_DIM_ALPHA))
                .offset(y = -px(lift)),
        )
    }
}

/** A bar button with no tab behind it: Simplified's three. */
@Composable
private fun NavTile(
    icon: ImageVector,
    /** The one you are on is the bright one; the rest step back, as in the tabs. */
    active: Boolean = false,
    /** Trims a glyph that draws larger than the rest at the same box size. */
    scale: Float = SEARCH_SCALE,
    /** The square the glyph centres in, and the size of its tap target. */
    box: Int = NAV_BOX_PX,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(px(box))
            .appClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            icon = icon,
            // The size the tabs are drawn at, so the bar reads as one row
            // whichever layout is showing.
            size = px(NAV_ICON_PX) * scale,
            tint = LightThemeTokens.colors.content,
            modifier = if (active) Modifier else Modifier.alpha(NAV_DIM_ALPHA),
        )
    }
}

/** Height of the library's bottom bar: 4 grid units, as the stock LightOS bar. */
private const val BOTTOM_BAR_PX = 160

/** A bar button's square, and its tap target. */
private const val NAV_BOX_PX = 144

/** The glyph box every bar icon is scaled from. */
private const val NAV_ICON_PX = 114

/** Matches the player's secondary row: the app's one "present but not chosen". */
private const val NAV_DIM_ALPHA = 0.45f

/**
 * Bringing the tab glyphs closer to one height.
 *
 * A Material icon fills its 24dp viewport differently depending on its shape, so
 * at one box size the bar ran from 66px (playlists) to 90px (artists): four icons
 * in three sizes. `*_DRAWN_PX` is what each glyph drew at the shared box, measured
 * off the panel, and the target is what it should draw instead. Re-measure rather
 * than guess if a glyph changes. The playlist glyph is the reference.
 */
private const val ARTISTS_DRAWN_PX = 90
private const val ALBUMS_DRAWN_PX = 105 // 89 measured under a 0.85 trim, so 89 / 0.85
private const val SONGS_DRAWN_PX = 86

/**
 * The simplified bar's library glyph, which stands in for the Albums tab. Its own
 * measure, not the album stack's heavier trim. An estimate, not a panel
 * measurement: re-measure if it reads off.
 */
private const val LIBRARY_DRAWN_PX = 95

/** Where the tall glyphs land: above the playlist's own 66, well under their 90. */
private const val TALL_TARGET_PX = 72

/** A mic is the narrowest of them, so it carries a little more height than the rest. */
private const val ARTISTS_TARGET_PX = 75

private fun navScale(drawnPx: Int, targetPx: Int): Float = targetPx.toFloat() / drawnPx

/**
 * How far the outer edge of a glyph sits from the screen's edge, for the bars
 * that stand against them: the simplified nav bar, and the queue's shuffle and
 * repeat. About where a list row's artwork begins, and half the bar's height.
 */
const val EDGE_GLYPH_PX = 80

/** A shade off the reference size, and a shade higher in its box. Judged by eye. */
private const val PLAYLISTS_SCALE = 0.94f
private const val PLAYLISTS_LIFT_PX = 5

/** The magnifier, at the same reference size as the rest of the bar. */
private const val SEARCH_SCALE = PLAYLISTS_SCALE

/**
 * The waveform at the 80px box LightOS itself gives it, two grid units: 2px to
 * each unit of its 40-unit viewport, so its bars land on whole pixels. A size
 * rather than an equalisation like the tab scales above, so the scale falls out
 * of it.
 */
private const val NOW_PLAYING_PX = 80
private val NOW_PLAYING_SCALE = NOW_PLAYING_PX.toFloat() / NAV_ICON_PX
