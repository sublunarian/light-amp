package com.sublunar.amp.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * A plain plus for Light's set, which has only the ringed one (the SDK's
 * `ic_add_white`). Light's cross, centred in that glyph's box, with its bars
 * thickened and lengthened to weigh what Material's plus does at the same size
 * (4px bars, 32px arms in a 54px box, measured on the phone).
 */
val PlusSymbol: ImageVector by lazy {
    ImageVector.Builder(
        name = "PlusSymbol",
        defaultWidth = 30.4.dp,
        defaultHeight = 30.67.dp,
        viewportWidth = 30.4f,
        viewportHeight = 30.67f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(PLUS_PATH).toNodes(),
            fill = SolidColor(Color.White),
        )
    }.build()
}

private const val PLUS_PATH =
    "M 16.14,5.92 L 13.86,5.92 L 13.86,13.86 L 5.92,13.86 L 5.92,16.14 L 13.86,16.14 L 13.86,24.08 " +
        "L 16.14,24.08 L 16.14,16.14 L 24.08,16.14 L 24.08,13.86 L 16.14,13.86 L 16.14,5.92 Z"
