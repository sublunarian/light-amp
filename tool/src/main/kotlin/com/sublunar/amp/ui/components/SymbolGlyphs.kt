package com.sublunar.amp.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/*
 * Glyphs from Material Symbols (Outlined, 24px), which the Material icons
 * library Amp vendors from (MaterialGlyphs.kt) doesn't carry. Each path is the
 * one in the SVG Google serves for it, unchanged.
 */

/** Material Symbols' `library_music` at weight 300: at the bar's size, its lines weigh what the Albums stack's do. */
val LibraryMusicSymbol: ImageVector by lazy { symbol("LibraryMusicSymbol", LIBRARY_MUSIC_PATH) }

/** Material Symbols' `media_output` at weight 300, the player's way to a speaker, drawn [MEDIA_OUTPUT_INSET] in to stand as large as Material's cast did. */
val MediaOutputSymbol: ImageVector by lazy { symbol("MediaOutputSymbol", MEDIA_OUTPUT_PATH, inset = MEDIA_OUTPUT_INSET) }

/** Its 19 units drawn across the 22 Material's cast spans: 24 × 19/22 is 20.7 units, 1.6 off each side. */
private const val MEDIA_OUTPUT_INSET = 1.6f

/** Material Symbols' `theater_comedy` at weight 300: the two masks, for Genres. */
val TheaterComedySymbol: ImageVector by lazy { symbol("TheaterComedySymbol", THEATER_COMEDY_PATH) }

/** Material Symbols' `speaker` at weight 300: an outlined speaker, for the outputs that are one. */
val SpeakerSymbol: ImageVector by lazy { symbol("SpeakerSymbol", SPEAKER_PATH) }

/** Material Symbols' `music_cast` at weight 300, for what is cast to that isn't a speaker, drawn [MEDIA_OUTPUT_INSET] in as media output is: both span 19 units. */
val MusicCastSymbol: ImageVector by lazy { symbol("MusicCastSymbol", MUSIC_CAST_PATH, inset = MEDIA_OUTPUT_INSET) }

/** [path] in its 24-unit box, less [inset] units off each side so it fills more of it. */
private fun symbol(name: String, path: String, inset: Float = 0f): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f - 2 * inset,
        viewportHeight = 24f - 2 * inset,
    ).apply {
        addGroup(translationX = -inset, translationY = -inset)
        addPath(pathData = PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.White))
        clearGroup()
    }.build()

private const val LIBRARY_MUSIC_PATH =
    "M12.35,14.85q0.94,0 1.6-0.65t0.65-1.6V6.98h2.87V5.21H13.71v5.65q-0.29-0.26-0.63-0.39t-0.74-0.13" +
        "q-0.94,0-1.6,0.65T10.1,12.6t0.65,1.6t1.6,0.65ZM8.06,17.5q-0.76,0-1.28-0.52T6.25,15.69V4.31" +
        "q0-0.76 0.52-1.28T8.06,2.5H19.44q0.76,0 1.28,0.52t0.52,1.28V15.69q0,0.76-0.52,1.28T19.44,17.5H8.06Z" +
        "m0-1.5H19.44q0.12,0 0.21-0.1t0.1-0.21V4.31q0-0.12-0.1-0.21T19.44,4H8.06Q7.94,4 7.85,4.1T7.75,4.31" +
        "V15.69q0,0.12 0.1,0.21T8.06,16Zm-3.5,5Q3.8,21 3.28,20.47T2.75,19.19V6.31h1.5V19.19q0,0.12 0.1,0.21" +
        "t0.21,0.1H17.44V21H4.56ZM7.75,4q0,0 0,0.09t0,0.22V15.69q0,0.13 0,0.22t0,0.09q0,0 0-0.09t0-0.22V4.31" +
        "q0-0.13 0-0.22T7.75,4Z"

private const val MEDIA_OUTPUT_PATH =
    "M8.56,11ZM4.31,19.5q-0.75,0-1.28-0.53T2.5,17.69V4.31q0-0.75 0.53-1.28T4.31,2.5h8.5q0.75,0 1.28,0.53" +
        "t0.53,1.28V7.93q-0.39,0.05-0.77,0.15T13.12,8.32V4.31q0-0.13-0.09-0.22T12.81,4H4.31Q4.17,4 4.09,4.09" +
        "T4,4.31V17.69q0,0.13 0.09,0.22T4.31,18H7.92v1.5H4.31Zm3.62-2.76V15.87q0-0.17 0.01-0.33T7.97,15.2" +
        "q-0.55-0.18-0.9-0.66T6.71,13.46q0-0.77 0.54-1.31t1.31-0.54q0.13,0 0.24,0.02T9.03,11.7q0.2-0.33 0.43-0.65" +
        "t0.49-0.6Q9.65,10.28 9.29,10.2T8.56,10.12q-1.39,0-2.37,0.98T5.21,13.46q0,1.24 0.78,2.15t1.94,1.13Z" +
        "M9.55,8.09q0.41-0.41 0.41-1T9.55,6.1t-1-0.41T7.56,6.1t-0.41,1T7.56,8.09t1,0.41T9.55,8.09ZM13.5,21.5" +
        "H11.92q-0.75,0-1.28-0.53t-0.53-1.28V15.73q0-2.37 1.66-4.03t4.03-1.66t4.03,1.66t1.66,4.03v3.96" +
        "q0,0.75-0.53,1.28T19.69,21.5H18.12V16.81h2.19V15.73q0-1.88-1.31-3.19t-3.19-1.31t-3.19,1.31t-1.31,3.19" +
        "v1.08H13.5V21.5Z"

private const val THEATER_COMEDY_PATH =
    "M18.75,7.4q0.38,0 0.64-0.26T19.65,6.5T19.39,5.86T18.75,5.6T18.11,5.86T17.85,6.5t0.26,0.64T18.75,7.4Z" +
        "m-4,0q0.38,0 0.64-0.26T15.65,6.5T15.39,5.86T14.75,5.6T14.11,5.86T13.85,6.5t0.26,0.64T14.75,7.4Z" +
        "m-0.38,3.4h4.77q-0.05-0.77-0.76-1.24T16.75,9.1q-0.94,0-1.65,0.47T14.37,10.8ZM3.36,19.9" +
        "Q1.75,18.29 1.75,16V9.5h11V16q0,2.29-1.6,3.9T7.25,21.5t-3.9-1.6Zm6.72-1.07q1.18-1.18 1.18-2.82V11h-8v5" +
        "q0,1.65 1.17,2.82T7.25,20t2.83-1.18Zm6.67-4.33q-0.66,0-1.33-0.16t-1.19-0.47l0.02-1.77q0.54,0.42 1.17,0.66" +
        "T16.75,13q1.65,0 2.82-1.18T20.75,9V4h-8V8h-1.5V2.5h11V9q0,2.29-1.6,3.9t-3.9,1.6ZM5.25,14.4" +
        "q0.38,0 0.64-0.26T6.15,13.5T5.89,12.86T5.25,12.6T4.61,12.86T4.35,13.5t0.26,0.64T5.25,14.4Z" +
        "m4.64-0.26q0.26-0.26 0.26-0.64T9.89,12.86T9.25,12.6T8.61,12.86T8.35,13.5t0.26,0.64T9.25,14.4" +
        "t0.64-0.26ZM8.9,17.33q0.71-0.47 0.74-1.24H4.87q0.03,0.77 0.74,1.24T7.25,17.8T8.9,17.33Z" +
        "M7.25,15.5Zm9.5-7Z"

private const val SPEAKER_PATH =
    "M16.69,21.5H7.31q-0.75,0-1.28-0.53T5.5,19.69V4.31q0-0.75 0.53-1.28T7.31,2.5h9.38q0.75,0 1.28,0.53" +
        "T18.5,4.31V19.69q0,0.75-0.53,1.28T16.69,21.5Zm0-1.5q0.13,0 0.22-0.09T17,19.69V4.31q0-0.13-0.09-0.22" +
        "T16.69,4H7.31Q7.17,4 7.09,4.09T7,4.31V19.69q0,0.13 0.09,0.22T7.31,20h9.38ZM13.28,8.28Q13.81,7.75 13.81,7" +
        "T13.28,5.72T12,5.19T10.72,5.72T10.19,7t0.53,1.28T12,8.81t1.28-0.53Zm1.36,9.36q1.09-1.09 1.09-2.64" +
        "t-1.09-2.64T12,11.27T9.36,12.36T8.27,15t1.09,2.64T12,18.73t2.64-1.09Zm-4.21-1.06Q9.77,15.92 9.77,15" +
        "t0.65-1.58T12,12.77t1.58,0.65T14.23,15t-0.65,1.58T12,17.23t-1.58-0.65ZM7,4Q7,4 7,4.09T7,4.31V19.69" +
        "q0,0.13 0,0.22T7,20q0,0 0-0.09t0-0.22V4.31Q7,4.17 7,4.09T7,4Z"

private const val MUSIC_CAST_PATH =
    "M11.63,18.47Q10.61,17.45 10.61,16t1.03-2.47t2.47-1.03q0.57,0 1.08,0.17t0.92,0.51V4.35h5.44V7H17.61v9" +
        "q0,1.44-1.03,2.47T14.11,19.5t-2.47-1.03ZM2.45,16q0-2.41 0.92-4.53t2.5-3.7t3.7-2.5t4.53-0.92v1.5" +
        "q-2.08,0-3.93,0.8T6.94,8.83T4.75,12.05T3.95,16H2.45Zm4.08,0q0-1.56 0.6-2.95t1.63-2.41t2.4-1.62" +
        "t2.94-0.59v1.5q-2.53,0-4.3,1.77T8.03,16H6.53Z"
