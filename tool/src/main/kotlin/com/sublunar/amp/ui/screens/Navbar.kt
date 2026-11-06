package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.sublunar.amp.App
import com.sublunar.amp.data.LayoutMode
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.TabGlyphs
import com.sublunar.amp.ui.components.appClickable
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
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
    onNowPlaying: () -> Unit,
    /** Simplified's library button — see [LibraryNav.libraryIndex]. */
    onBrowse: () -> Unit,
    /** False on the library page itself, where Simplified's library button would lead nowhere. */
    showLibrary: Boolean = true,
) {
    // The eager state rather than the preference Flow: the bar is on every
    // screen, and a fresh collect draws a frame of the wrong bar.
    val simplified = App.settings.layoutMode.state.collectAsState().value == LayoutMode.SIMPLIFIED
    if (simplified) {
        SimplifiedNavbar(onSearch, onNowPlaying, onBrowse, showLibrary)
    } else {
        StandardNavbar(current, onSelect, onNowPlaying)
    }
}

/**
 * Simplified layout: search, the library and the player as the phone's own three
 * buttons, laid out as the SDK's LightBottomBar lays out three: start, centre and
 * end, each glyph two grid units, its outer edge two units from the screen's.
 * Buttons, not tabs: all three full white, none lit. On the library page itself
 * its own button would lead nowhere, so its slot stands empty and the others
 * keep their places: the slot only ever means Library.
 */
@Composable
private fun SimplifiedNavbar(
    onSearch: () -> Unit,
    onNowPlaying: () -> Unit,
    onBrowse: () -> Unit,
    showLibrary: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(px(BOTTOM_BAR_PX))
            .padding(horizontal = px(EDGE_GLYPH_PX - (BOTTOM_BAR_PX - SDK_GLYPH_PX) / 2)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarSlot(Alignment.CenterStart) {
            BarButton(onSearch) { LightIcon(LightIcons.SEARCH, size = SDK_GLYPH_UNITS) }
        }
        BarSlot(Alignment.Center) {
            if (showLibrary) TextBarButton("HOME", onBrowse)
        }
        BarSlot(Alignment.CenterEnd) {
            BarButton(onNowPlaying) {
                AppIcon(AppIcons.Waveform, size = px(SDK_GLYPH_PX), tint = LightThemeTokens.colors.content)
            }
        }
    }
}

@Composable
private fun RowScope.BarSlot(align: Alignment, content: @Composable () -> Unit) {
    Box(Modifier.weight(1f), contentAlignment = align) { content() }
}

/** The bar's height square, the glyph centred: a tap target the full height of the bar. */
@Composable
private fun BarButton(onClick: () -> Unit, glyph: @Composable () -> Unit) {
    Box(
        modifier = Modifier.size(px(BOTTOM_BAR_PX)).appClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { glyph() }
}

/**
 * A worded bar button as the SDK's LightBottomBar draws one (Weather's THIS WEEK):
 * Button type, one line, its tap target the bar's height and the word's width.
 */
@Composable
private fun TextBarButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(px(BOTTOM_BAR_PX))
            .appClickable(onClick = onClick)
            .padding(horizontal = px(TEXT_BUTTON_PAD_PX)),
        contentAlignment = Alignment.Center,
    ) { LightText(text, variant = LightTextVariant.Button, maxLines = 1) }
}

/**
 * Standard layout: the four tabs across the bar, then the player. Every glyph an
 * outline that never fills: the tab you are on white, the rest the SDK's grey.
 */
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
            TabGlyphs.Playlist,
            current == LibraryTab.PLAYLISTS,
            scale = tabGlyphScale(LibraryTab.PLAYLISTS),
            lift = tabGlyphLiftPx(LibraryTab.PLAYLISTS),
        ) { onSelect(LibraryTab.PLAYLISTS) }
        // LightOS's own microphone.
        NavIcon(
            TabGlyphs.Microphone,
            current == LibraryTab.ARTISTS,
            scale = tabGlyphScale(LibraryTab.ARTISTS),
        ) { onSelect(LibraryTab.ARTISTS) }
        NavIcon(
            AppIcons.AlbumStack,
            current == LibraryTab.ALBUMS,
            scale = tabGlyphScale(LibraryTab.ALBUMS),
        ) { onSelect(LibraryTab.ALBUMS) }
        NavIcon(
            AppIcons.MusicNoteOutline,
            current == LibraryTab.SONGS,
            scale = tabGlyphScale(LibraryTab.SONGS),
        ) { onSelect(LibraryTab.SONGS) }
        // The player after the four ways of looking at the library: the one
        // thing here you arrive at rather than browse to. Never lit, since it
        // hides this bar.
        NavIcon(
            AppIcons.Waveform,
            active = false,
            scale = NOW_PLAYING_SCALE,
        ) { onNowPlaying() }
    }
}

@Composable
private fun NavIcon(
    icon: ImageVector,
    /** The tab you are on: white, where the rest are the SDK's grey. */
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
            tint = if (active) LightThemeTokens.colors.content else LightThemeTokens.colors.contentSecondary,
            modifier = Modifier.offset(y = -px(lift)),
        )
    }
}

/** A bar button with no tab behind it: Simplified's three. */
/** Height of the library's bottom bar: 4 grid units, as the stock LightOS bar. */
private const val BOTTOM_BAR_PX = 160

/** A bar button's square, and its tap target. */
private const val NAV_BOX_PX = 144

/** The glyph box every bar icon is scaled from. */
internal const val NAV_ICON_PX = 114

/**
 * Bringing the tab glyphs closer to one height.
 *
 * A Material icon fills its 24dp viewport differently depending on its shape, so
 * at one box size the bar ran from 66px (playlists) to 90px (artists): four icons
 * in three sizes. `*_DRAWN_PX` is what each glyph drew at the shared box, measured
 * off the panel, and the target is what it should draw instead. Re-measure rather
 * than guess if a glyph changes. The playlist glyph is the reference.
 */
private const val ALBUMS_DRAWN_PX = 105 // 89 measured under a 0.85 trim, so 89 / 0.85
private const val SONGS_DRAWN_PX = 86

/** The artists' microphone, from the glyph's own box, not yet measured on the panel. */
private const val LIGHT_MIC_DRAWN_PX = 108 // 38 of its 40 units


/** The SDK's bar glyph: two grid units (LightBarButtonDefaults.ICON_SIZE_UNITS). */
private const val SDK_GLYPH_UNITS = 2f
private const val SDK_GLYPH_PX = 80

/** Widens a worded button's tap target past the word's own edges. */
private const val TEXT_BUTTON_PAD_PX = 40

/** Where the tall glyphs land: above the playlist's own 66, well under their 90. */
private const val TALL_TARGET_PX = 72

/** A mic is the narrowest of them, so it carries a little more height than the rest. */
private const val ARTISTS_TARGET_PX = 75

private fun navScale(drawnPx: Int, targetPx: Int): Float = targetPx.toFloat() / drawnPx

/** A tab glyph's trim inside a [NAV_ICON_PX] box, so the bar and the home list match. */
internal fun tabGlyphScale(tab: LibraryTab): Float = when (tab) {
    LibraryTab.PLAYLISTS -> PLAYLISTS_SCALE
    LibraryTab.ARTISTS -> navScale(LIGHT_MIC_DRAWN_PX, ARTISTS_TARGET_PX)
    LibraryTab.ALBUMS -> navScale(ALBUMS_DRAWN_PX, TALL_TARGET_PX)
    LibraryTab.SONGS -> navScale(SONGS_DRAWN_PX, TALL_TARGET_PX)
}

/** How far a tab glyph rises inside a [NAV_ICON_PX] box. */
internal fun tabGlyphLiftPx(tab: LibraryTab): Int = if (tab == LibraryTab.PLAYLISTS) PLAYLISTS_LIFT_PX else 0

/**
 * The home list's heart, which has no tab: 18.35 of its 24 units tall, so 87 of a
 * [NAV_ICON_PX] box, brought to the playlist glyph's height (66 under its 0.94),
 * since both are wide shapes.
 */
private const val HEART_DRAWN_PX = 87
private const val WIDE_TARGET_PX = 62
internal val LIKED_GLYPH_SCALE = navScale(HEART_DRAWN_PX, WIDE_TARGET_PX)

/**
 * Light's download arrow, its bar included: 49 units tall to 40 wide, so it fills
 * a [NAV_ICON_PX] square's height, brought to the tall tab glyphs' height.
 */
internal val DOWNLOADS_GLYPH_SCALE = navScale(NAV_ICON_PX, TALL_TARGET_PX)

/**
 * How far the outer edge of a glyph sits from the screen's edge, for the bars
 * that stand against them: the simplified nav bar, and the queue's shuffle and
 * repeat. About where a list row's artwork begins, and half the bar's height.
 */
const val EDGE_GLYPH_PX = 80

/** A shade off the reference size, and a shade higher in its box. Judged by eye. */
private const val PLAYLISTS_SCALE = 0.94f
private const val PLAYLISTS_LIFT_PX = 5


/**
 * The waveform at the 80px box LightOS itself gives it, two grid units: 2px to
 * each unit of its 40-unit viewport, so its bars land on whole pixels. A size
 * rather than an equalisation like the tab scales above, so the scale falls out
 * of it.
 */
private const val NOW_PLAYING_PX = 80
private val NOW_PLAYING_SCALE = NOW_PLAYING_PX.toFloat() / NAV_ICON_PX
