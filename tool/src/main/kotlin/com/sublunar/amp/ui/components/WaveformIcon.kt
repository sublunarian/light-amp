package com.sublunar.amp.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * LightOS's own waveform (rounded bars of stepped heights), from the SDK's
 * `ic_audio_message_white` drawable. Rebuilt as an [ImageVector] so it goes
 * wherever the other icons go (`HeaderAction` takes vectors) and tints with the theme.
 */
val WaveformSymbol: ImageVector by lazy {
    ImageVector.Builder(
        name = "WaveformSymbol",
        // One dp per viewport unit, as the drawable declares it, so the path is
        // scaled once (a default of 24 would scale it down and back up).
        defaultWidth = 40.dp,
        defaultHeight = 40.dp,
        viewportWidth = 40f,
        viewportHeight = 40f,
    ).apply {
        WAVEFORM_PATHS.forEach { d ->
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = SolidColor(Color.White),
            )
        }
    }.build()
}

/** The six bars, shortest at the edges, straight from the SDK drawable. */
private val WAVEFORM_PATHS = listOf(
    "m2.6 22.3a2.48 2.48 0 0 1-2.5-2.5v-2.5a2.45 2.45 0 0 1 4.9-0.1v2.6a2.39 2.39 0 0 1-2.4 2.5",
    "m9.5 28.6a2.48 2.48 0 0 1-2.5-2.5v-13.1a2.45 2.45 0 1 1 4.9-0.1v13.2a2.34 2.34 0 0 1-2.4 2.5",
    "m16.5 35.2a2.48 2.48 0 0 1-2.5-2.5v-25.4a2.45 2.45 0 1 1 4.9-0.1v25.5a2.34 2.34 0 0 1-2.4 2.5",
    "M23.8,24.1h-.7A2.11,2.11,0,0,1,21,22V16.2a2.11,2.11,0,0,1,2.1-2.1h.7a2.11,2.11,0,0,1,2.1,2.1V22a2.05,2.05,0,0,1-2.1,2.1",
    "m30.4 29.1a2.48 2.48 0 0 1-2.5-2.5v-18a2.45 2.45 0 0 1 4.9-0.1v18.1a2.34 2.34 0 0 1-2.4 2.5",
    "m37.7 21h-0.7a2.22 2.22 0 0 1-2.2-2.2v-1.3a2.22 2.22 0 0 1 2.2-2.2h0.6a2.22 2.22 0 0 1 2.2 2.2v1.3a2.2 2.2 0 0 1-2.1 2.2",
)
