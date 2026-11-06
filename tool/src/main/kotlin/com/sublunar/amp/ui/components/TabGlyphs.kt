package com.sublunar.amp.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Glyphs Material and the SDK don't hand out as vectors: LightOS's microphone
 * (the SDK has it only as a drawable), used for the Artists tab and the library
 * button, and, for Outline Tab Icons, Material Design Icons' playlist outline.
 */
object TabGlyphs {
    /** LightOS's microphone (the SDK's `ic_microphone_white`): an outline, black inside. */
    val Microphone: ImageVector = glyph("Light.Microphone", 40f, MIC_STAND, MIC_OUTLINE)

    /** Material Design Icons' `playlist-music-outline` (Pictogrammers, Apache 2.0): its note head a ring. */
    val Playlist: ImageVector = glyph("Mdi.PlaylistMusicOutline", 24f, PLAYLIST_MUSIC_OUTLINE)
}

private fun glyph(name: String, viewport: Float, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = viewport.dp,
        defaultHeight = viewport.dp,
        viewportWidth = viewport,
        viewportHeight = viewport,
    ).apply {
        paths.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.White)) }
    }.build()

private const val MIC_STAND =
    "M32.9,22.97c0,0.01,0,0.02,0,0.03c0,7.18-5.82,13-13,13s-13-5.82-13-13c0-0.01,0-0.02,0-0.03h-3" +
        "c0,0.01,0,0.02,0,0.03c0,8.84,7.16,16,16,16s16-7.16,16-16c0-0.01,0-0.02,0-0.03H32.9z"

/** The body with its hollow: the drawable's second path. */
private const val MIC_OUTLINE =
    "M28.9,10v13c0,4.97-4.03,9-9,9s-9-4.03-9-9V10c0-4.97,4.03-9,9-9S28.9,5.03,28.9,10z" +
        "M19.9,4c-3.31,0-6,2.69-6,6v13c0,3.31,2.69,6,6,6s6-2.69,6-6V10C25.9,6.69,23.21,4,19.9,4z"

private const val PLAYLIST_MUSIC_OUTLINE =
    "M15 6v2H3V6zm0 4v2H3v-2zM3 16v-2h8v2zM17 6h5v2h-3v9a3 3 0 0 1-3 3a3 3 0 0 1-3-3a3 3 0 0 1 3-3" +
        "c.35 0 .69.07 1 .18zm-1 10a1 1 0 0 0-1 1a1 1 0 0 0 1 1a1 1 0 0 0 1-1a1 1 0 0 0-1-1"
