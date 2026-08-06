package com.sublunar.amp.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Material Symbols "artist" — a figure with a note, which the classic Material
 * Icons set has no equivalent of.
 *
 * Built from Google's own path data rather than drawn by hand: Symbols are a
 * different artifact from the `material-icons-extended` the rest of the app
 * pulls from, and adding that dependency for one glyph would pull thousands.
 * The source viewBox is 0 -960 960 960 (Symbols' own y-up coordinates), so the
 * vector declares those bounds and the path is used unchanged.
 */
val ArtistSymbol: ImageVector by lazy {
    ImageVector.Builder(
        name = "ArtistSymbol",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(ARTIST_PATH).toNodes(),
            fill = SolidColor(Color.White),
        )
    }.build()
}

/**
 * Shifted down by 960 from the published path, which is authored in Symbols'
 * y-up space; Compose's viewport starts at 0.
 */
private const val ARTIST_PATH =
    "M740 400h140v80h-80v220q0 42-29 71t-71 29q-42 0-71-29t-29-71q0-42 29-71t71-29q8 0 18 " +
        "1.5t22 6.5v-208ZM120 800v-112q0-35 17.5-63t46.5-43q62-31 126-46.5T440 520q42 0 " +
        "83.5 6.5T607 546q-20 12-36 29t-28 37q-26-6-51.5-9t-51.5-3q-57 0-112 14t-108 40q-9 " +
        "5-14.5 14t-5.5 20v32h321q2 20 9.5 40t20.5 40H120Zm207-367q-47-47-47-113t47-113q47-47 " +
        "113-47t113 47q47 47 47 113t-47 113q-47 47-113 47t-113-47Zm169.5-56.5Q520 353 520 " +
        "320t-23.5-56.5Q473 240 440 240t-56.5 23.5Q360 287 360 320t23.5 56.5Q407 400 440 " +
        "400t56.5-23.5ZM440 320Zm0 400Z"
