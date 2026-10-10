package com.sublunar.amp.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The Material icons Amp draws, carried in its own source.
 *
 * They came from `androidx.compose.material:material-icons-extended`, which is
 * on Light's allow-list but not in the offline Gradle cache their store builder
 * builds against — that cache holds only what the SDK's own sample tool
 * resolves — so a store build could not resolve it. Only the glyphs Amp uses are
 * here, which is all R8 ever kept of the artifact anyway.
 *
 * The path data is Google's Material Icons (Apache License 2.0), unchanged from
 * material-icons-extended 1.7.8: each vector was read out of the library's own
 * ImageVector and printed back as Kotlin, not redrawn. MaterialGlyphsTest holds
 * every one against the library, so any drift fails the build's tests.
 *
 * The names mirror Material's, so `Icons.Filled.Album` reads the same as it did
 * with the artifact. To add a glyph, list it in MaterialGlyphsTest with `null`
 * for the vendored side; the test then fails with the source to paste here.
 */
object Icons {
    object Filled {
        val Add: ImageVector by glyph("Filled.Add") {
            shape {
                moveTo(19.0f, 13.0f)
                horizontalLineToRelative(-6.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-6.0f)
                horizontalLineTo(5.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(6.0f)
                verticalLineTo(5.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(2.0f)
                close()
            }
        }

        val AddCircleOutline: ImageVector by glyph("Filled.AddCircleOutline") {
            shape {
                moveTo(13.0f, 7.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(4.0f)
                lineTo(7.0f, 11.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(4.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-4.0f)
                lineTo(13.0f, 7.0f)
                close()
                moveTo(12.0f, 2.0f)
                curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
                reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
                close()
                moveTo(12.0f, 20.0f)
                curveToRelative(-4.41f, 0.0f, -8.0f, -3.59f, -8.0f, -8.0f)
                reflectiveCurveToRelative(3.59f, -8.0f, 8.0f, -8.0f)
                reflectiveCurveToRelative(8.0f, 3.59f, 8.0f, 8.0f)
                reflectiveCurveToRelative(-3.59f, 8.0f, -8.0f, 8.0f)
                close()
            }
        }

        val Album: ImageVector by glyph("Filled.Album") {
            shape {
                moveTo(12.0f, 2.0f)
                curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
                reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
                close()
                moveTo(12.0f, 16.5f)
                curveToRelative(-2.49f, 0.0f, -4.5f, -2.01f, -4.5f, -4.5f)
                reflectiveCurveTo(9.51f, 7.5f, 12.0f, 7.5f)
                reflectiveCurveToRelative(4.5f, 2.01f, 4.5f, 4.5f)
                reflectiveCurveToRelative(-2.01f, 4.5f, -4.5f, 4.5f)
                close()
                moveTo(12.0f, 11.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                reflectiveCurveToRelative(0.45f, 1.0f, 1.0f, 1.0f)
                reflectiveCurveToRelative(1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                close()
            }
        }

        val ArrowDownward: ImageVector by glyph("Filled.ArrowDownward") {
            shape {
                moveTo(20.0f, 12.0f)
                lineToRelative(-1.41f, -1.41f)
                lineTo(13.0f, 16.17f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(12.17f)
                lineToRelative(-5.58f, -5.59f)
                lineTo(4.0f, 12.0f)
                lineToRelative(8.0f, 8.0f)
                lineToRelative(8.0f, -8.0f)
                close()
            }
        }

        val ArrowDropDown: ImageVector by glyph("Filled.ArrowDropDown") {
            shape {
                moveTo(7.0f, 10.0f)
                lineToRelative(5.0f, 5.0f)
                lineToRelative(5.0f, -5.0f)
                close()
            }
        }

        val ArrowUpward: ImageVector by glyph("Filled.ArrowUpward") {
            shape {
                moveTo(4.0f, 12.0f)
                lineToRelative(1.41f, 1.41f)
                lineTo(11.0f, 7.83f)
                verticalLineTo(20.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(7.83f)
                lineToRelative(5.58f, 5.59f)
                lineTo(20.0f, 12.0f)
                lineToRelative(-8.0f, -8.0f)
                lineToRelative(-8.0f, 8.0f)
                close()
            }
        }

        val Bluetooth: ImageVector by glyph("Filled.Bluetooth") {
            shape {
                moveTo(17.71f, 7.71f)
                lineTo(12.0f, 2.0f)
                horizontalLineToRelative(-1.0f)
                verticalLineToRelative(7.59f)
                lineTo(6.41f, 5.0f)
                lineTo(5.0f, 6.41f)
                lineTo(10.59f, 12.0f)
                lineTo(5.0f, 17.59f)
                lineTo(6.41f, 19.0f)
                lineTo(11.0f, 14.41f)
                lineTo(11.0f, 22.0f)
                horizontalLineToRelative(1.0f)
                lineToRelative(5.71f, -5.71f)
                lineToRelative(-4.3f, -4.29f)
                lineToRelative(4.3f, -4.29f)
                close()
                moveTo(13.0f, 5.83f)
                lineToRelative(1.88f, 1.88f)
                lineTo(13.0f, 9.59f)
                lineTo(13.0f, 5.83f)
                close()
                moveTo(14.88f, 16.29f)
                lineTo(13.0f, 18.17f)
                verticalLineToRelative(-3.76f)
                lineToRelative(1.88f, 1.88f)
                close()
            }
        }

        val Cast: ImageVector by glyph("Filled.Cast") {
            shape {
                moveTo(21.0f, 3.0f)
                lineTo(3.0f, 3.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(2.0f)
                lineTo(3.0f, 5.0f)
                horizontalLineToRelative(18.0f)
                verticalLineToRelative(14.0f)
                horizontalLineToRelative(-7.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(7.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(23.0f, 5.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(1.0f, 18.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(3.0f)
                curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
                close()
                moveTo(1.0f, 14.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(2.76f, 0.0f, 5.0f, 2.24f, 5.0f, 5.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(0.0f, -3.87f, -3.13f, -7.0f, -7.0f, -7.0f)
                close()
                moveTo(1.0f, 10.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(4.97f, 0.0f, 9.0f, 4.03f, 9.0f, 9.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(0.0f, -6.08f, -4.93f, -11.0f, -11.0f, -11.0f)
                close()
            }
        }

        val CheckCircle: ImageVector by glyph("Filled.CheckCircle") {
            shape {
                moveTo(12.0f, 2.0f)
                curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
                reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
                close()
                moveTo(10.0f, 17.0f)
                lineToRelative(-5.0f, -5.0f)
                lineToRelative(1.41f, -1.41f)
                lineTo(10.0f, 14.17f)
                lineToRelative(7.59f, -7.59f)
                lineTo(19.0f, 8.0f)
                lineToRelative(-9.0f, 9.0f)
                close()
            }
        }

        val Close: ImageVector by glyph("Filled.Close") {
            shape {
                moveTo(19.0f, 6.41f)
                lineTo(17.59f, 5.0f)
                lineTo(12.0f, 10.59f)
                lineTo(6.41f, 5.0f)
                lineTo(5.0f, 6.41f)
                lineTo(10.59f, 12.0f)
                lineTo(5.0f, 17.59f)
                lineTo(6.41f, 19.0f)
                lineTo(12.0f, 13.41f)
                lineTo(17.59f, 19.0f)
                lineTo(19.0f, 17.59f)
                lineTo(13.41f, 12.0f)
                close()
            }
        }

        val CloudOff: ImageVector by glyph("Filled.CloudOff") {
            shape {
                moveTo(19.35f, 10.04f)
                curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
                curveToRelative(-1.48f, 0.0f, -2.85f, 0.43f, -4.01f, 1.17f)
                lineToRelative(1.46f, 1.46f)
                curveTo(10.21f, 6.23f, 11.08f, 6.0f, 12.0f, 6.0f)
                curveToRelative(3.04f, 0.0f, 5.5f, 2.46f, 5.5f, 5.5f)
                verticalLineToRelative(0.5f)
                horizontalLineTo(19.0f)
                curveToRelative(1.66f, 0.0f, 3.0f, 1.34f, 3.0f, 3.0f)
                curveToRelative(0.0f, 1.13f, -0.64f, 2.11f, -1.56f, 2.62f)
                lineToRelative(1.45f, 1.45f)
                curveTo(23.16f, 18.16f, 24.0f, 16.68f, 24.0f, 15.0f)
                curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
                close()
                moveTo(3.0f, 5.27f)
                lineToRelative(2.75f, 2.74f)
                curveTo(2.56f, 8.15f, 0.0f, 10.77f, 0.0f, 14.0f)
                curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
                horizontalLineToRelative(11.73f)
                lineToRelative(2.0f, 2.0f)
                lineTo(21.0f, 20.73f)
                lineTo(4.27f, 4.0f)
                lineTo(3.0f, 5.27f)
                close()
                moveTo(7.73f, 10.0f)
                lineToRelative(8.0f, 8.0f)
                horizontalLineTo(6.0f)
                curveToRelative(-2.21f, 0.0f, -4.0f, -1.79f, -4.0f, -4.0f)
                reflectiveCurveToRelative(1.79f, -4.0f, 4.0f, -4.0f)
                horizontalLineToRelative(1.73f)
                close()
            }
        }

        val Dehaze: ImageVector by glyph("Filled.Dehaze") {
            shape {
                moveTo(2.0f, 15.5f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(20.0f)
                verticalLineToRelative(-2.0f)
                lineTo(2.0f, 15.5f)
                close()
                moveTo(2.0f, 10.5f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(20.0f)
                verticalLineToRelative(-2.0f)
                lineTo(2.0f, 10.5f)
                close()
                moveTo(2.0f, 5.5f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(20.0f)
                verticalLineToRelative(-2.0f)
                lineTo(2.0f, 5.5f)
                close()
            }
        }

        val DeleteOutline: ImageVector by glyph("Filled.DeleteOutline") {
            shape {
                moveTo(6.0f, 19.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(8.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(18.0f, 7.0f)
                lineTo(6.0f, 7.0f)
                verticalLineToRelative(12.0f)
                close()
                moveTo(8.0f, 9.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(10.0f)
                lineTo(8.0f, 19.0f)
                lineTo(8.0f, 9.0f)
                close()
                moveTo(15.5f, 4.0f)
                lineToRelative(-1.0f, -1.0f)
                horizontalLineToRelative(-5.0f)
                lineToRelative(-1.0f, 1.0f)
                lineTo(5.0f, 4.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(14.0f)
                lineTo(19.0f, 4.0f)
                close()
            }
        }

        val Download: ImageVector by glyph("Filled.Download") {
            shape {
                moveTo(5.0f, 20.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(20.0f)
                close()
                moveTo(19.0f, 9.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineTo(3.0f)
                horizontalLineTo(9.0f)
                verticalLineToRelative(6.0f)
                horizontalLineTo(5.0f)
                lineToRelative(7.0f, 7.0f)
                lineTo(19.0f, 9.0f)
                close()
            }
        }

        val DownloadDone: ImageVector by glyph("Filled.DownloadDone") {
            shape {
                moveTo(20.13f, 5.41f)
                lineToRelative(-1.41f, -1.41f)
                lineToRelative(-9.19f, 9.19f)
                lineToRelative(-4.25f, -4.24f)
                lineToRelative(-1.41f, 1.41f)
                lineToRelative(5.66f, 5.66f)
                close()
            }
            shape {
                moveTo(5.0f, 18.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-14.0f)
                close()
            }
        }

        val FastForward: ImageVector by glyph("Filled.FastForward") {
            shape {
                moveTo(4.0f, 18.0f)
                lineToRelative(8.5f, -6.0f)
                lineTo(4.0f, 6.0f)
                verticalLineToRelative(12.0f)
                close()
                moveTo(13.0f, 6.0f)
                verticalLineToRelative(12.0f)
                lineToRelative(8.5f, -6.0f)
                lineTo(13.0f, 6.0f)
                close()
            }
        }

        val FastRewind: ImageVector by glyph("Filled.FastRewind") {
            shape {
                moveTo(11.0f, 18.0f)
                lineTo(11.0f, 6.0f)
                lineToRelative(-8.5f, 6.0f)
                lineToRelative(8.5f, 6.0f)
                close()
                moveTo(11.5f, 12.0f)
                lineToRelative(8.5f, 6.0f)
                lineTo(20.0f, 6.0f)
                lineToRelative(-8.5f, 6.0f)
                close()
            }
        }

        val Favorite: ImageVector by glyph("Filled.Favorite") {
            shape {
                moveTo(12.0f, 21.35f)
                lineToRelative(-1.45f, -1.32f)
                curveTo(5.4f, 15.36f, 2.0f, 12.28f, 2.0f, 8.5f)
                curveTo(2.0f, 5.42f, 4.42f, 3.0f, 7.5f, 3.0f)
                curveToRelative(1.74f, 0.0f, 3.41f, 0.81f, 4.5f, 2.09f)
                curveTo(13.09f, 3.81f, 14.76f, 3.0f, 16.5f, 3.0f)
                curveTo(19.58f, 3.0f, 22.0f, 5.42f, 22.0f, 8.5f)
                curveToRelative(0.0f, 3.78f, -3.4f, 6.86f, -8.55f, 11.54f)
                lineTo(12.0f, 21.35f)
                close()
            }
        }

        val FavoriteBorder: ImageVector by glyph("Filled.FavoriteBorder") {
            shape {
                moveTo(16.5f, 3.0f)
                curveToRelative(-1.74f, 0.0f, -3.41f, 0.81f, -4.5f, 2.09f)
                curveTo(10.91f, 3.81f, 9.24f, 3.0f, 7.5f, 3.0f)
                curveTo(4.42f, 3.0f, 2.0f, 5.42f, 2.0f, 8.5f)
                curveToRelative(0.0f, 3.78f, 3.4f, 6.86f, 8.55f, 11.54f)
                lineTo(12.0f, 21.35f)
                lineToRelative(1.45f, -1.32f)
                curveTo(18.6f, 15.36f, 22.0f, 12.28f, 22.0f, 8.5f)
                curveTo(22.0f, 5.42f, 19.58f, 3.0f, 16.5f, 3.0f)
                close()
                moveTo(12.1f, 18.55f)
                lineToRelative(-0.1f, 0.1f)
                lineToRelative(-0.1f, -0.1f)
                curveTo(7.14f, 14.24f, 4.0f, 11.39f, 4.0f, 8.5f)
                curveTo(4.0f, 6.5f, 5.5f, 5.0f, 7.5f, 5.0f)
                curveToRelative(1.54f, 0.0f, 3.04f, 0.99f, 3.57f, 2.36f)
                horizontalLineToRelative(1.87f)
                curveTo(13.46f, 5.99f, 14.96f, 5.0f, 16.5f, 5.0f)
                curveToRelative(2.0f, 0.0f, 3.5f, 1.5f, 3.5f, 3.5f)
                curveToRelative(0.0f, 2.89f, -3.14f, 5.74f, -7.9f, 10.05f)
                close()
            }
        }

        val FilterNone: ImageVector by glyph("Filled.FilterNone") {
            shape {
                moveTo(3.0f, 5.0f)
                lineTo(1.0f, 5.0f)
                verticalLineToRelative(16.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(16.0f)
                verticalLineToRelative(-2.0f)
                lineTo(3.0f, 21.0f)
                lineTo(3.0f, 5.0f)
                close()
                moveTo(21.0f, 1.0f)
                lineTo(7.0f, 1.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(23.0f, 3.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(21.0f, 17.0f)
                lineTo(7.0f, 17.0f)
                lineTo(7.0f, 3.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(14.0f)
                close()
            }
        }

        val GraphicEq: ImageVector by glyph("Filled.GraphicEq") {
            shape {
                moveTo(7.0f, 18.0f)
                horizontalLineToRelative(2.0f)
                lineTo(9.0f, 6.0f)
                lineTo(7.0f, 6.0f)
                verticalLineToRelative(12.0f)
                close()
                moveTo(11.0f, 22.0f)
                horizontalLineToRelative(2.0f)
                lineTo(13.0f, 2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(20.0f)
                close()
                moveTo(3.0f, 14.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-4.0f)
                lineTo(3.0f, 10.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(15.0f, 18.0f)
                horizontalLineToRelative(2.0f)
                lineTo(17.0f, 6.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(12.0f)
                close()
                moveTo(19.0f, 10.0f)
                verticalLineToRelative(4.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineToRelative(-2.0f)
                close()
            }
        }

        val HistoryEdu: ImageVector by glyph("Filled.HistoryEdu") {
            shape {
                moveTo(9.0f, 4.0f)
                verticalLineToRelative(1.38f)
                curveToRelative(-0.83f, -0.33f, -1.72f, -0.5f, -2.61f, -0.5f)
                curveToRelative(-1.79f, 0.0f, -3.58f, 0.68f, -4.95f, 2.05f)
                lineToRelative(3.33f, 3.33f)
                horizontalLineToRelative(1.11f)
                verticalLineToRelative(1.11f)
                curveToRelative(0.86f, 0.86f, 1.98f, 1.31f, 3.11f, 1.36f)
                verticalLineTo(15.0f)
                horizontalLineTo(6.0f)
                verticalLineToRelative(3.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(10.0f)
                curveToRelative(1.66f, 0.0f, 3.0f, -1.34f, 3.0f, -3.0f)
                verticalLineTo(4.0f)
                horizontalLineTo(9.0f)
                close()
                moveTo(7.89f, 10.41f)
                verticalLineTo(8.26f)
                horizontalLineTo(5.61f)
                lineTo(4.57f, 7.22f)
                curveTo(5.14f, 7.0f, 5.76f, 6.88f, 6.39f, 6.88f)
                curveToRelative(1.34f, 0.0f, 2.59f, 0.52f, 3.54f, 1.46f)
                lineToRelative(1.41f, 1.41f)
                lineToRelative(-0.2f, 0.2f)
                curveToRelative(-0.51f, 0.51f, -1.19f, 0.8f, -1.92f, 0.8f)
                curveTo(8.75f, 10.75f, 8.29f, 10.63f, 7.89f, 10.41f)
                close()
                moveTo(19.0f, 17.0f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                reflectiveCurveToRelative(-1.0f, -0.45f, -1.0f, -1.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-6.0f)
                verticalLineToRelative(-2.59f)
                curveToRelative(0.57f, -0.23f, 1.1f, -0.57f, 1.56f, -1.03f)
                lineToRelative(0.2f, -0.2f)
                lineTo(15.59f, 14.0f)
                horizontalLineTo(17.0f)
                verticalLineToRelative(-1.41f)
                lineToRelative(-6.0f, -5.97f)
                verticalLineTo(6.0f)
                horizontalLineToRelative(8.0f)
                verticalLineTo(17.0f)
                close()
            }
        }

        val LibraryMusic: ImageVector by glyph("Filled.LibraryMusic") {
            shape {
                moveTo(20.0f, 2.0f)
                lineTo(8.0f, 2.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(12.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(22.0f, 4.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(18.0f, 7.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineToRelative(5.5f)
                curveToRelative(0.0f, 1.38f, -1.12f, 2.5f, -2.5f, 2.5f)
                reflectiveCurveTo(10.0f, 13.88f, 10.0f, 12.5f)
                reflectiveCurveToRelative(1.12f, -2.5f, 2.5f, -2.5f)
                curveToRelative(0.57f, 0.0f, 1.08f, 0.19f, 1.5f, 0.51f)
                lineTo(14.0f, 5.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(4.0f, 6.0f)
                lineTo(2.0f, 6.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                lineTo(4.0f, 20.0f)
                lineTo(4.0f, 6.0f)
                close()
            }
        }

        val LocalOffer: ImageVector by glyph("Filled.LocalOffer") {
            shape {
                moveTo(21.41f, 11.58f)
                lineToRelative(-9.0f, -9.0f)
                curveTo(12.05f, 2.22f, 11.55f, 2.0f, 11.0f, 2.0f)
                horizontalLineTo(4.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(7.0f)
                curveToRelative(0.0f, 0.55f, 0.22f, 1.05f, 0.59f, 1.42f)
                lineToRelative(9.0f, 9.0f)
                curveToRelative(0.36f, 0.36f, 0.86f, 0.58f, 1.41f, 0.58f)
                curveToRelative(0.55f, 0.0f, 1.05f, -0.22f, 1.41f, -0.59f)
                lineToRelative(7.0f, -7.0f)
                curveToRelative(0.37f, -0.36f, 0.59f, -0.86f, 0.59f, -1.41f)
                curveToRelative(0.0f, -0.55f, -0.23f, -1.06f, -0.59f, -1.42f)
                close()
                moveTo(5.5f, 7.0f)
                curveTo(4.67f, 7.0f, 4.0f, 6.33f, 4.0f, 5.5f)
                reflectiveCurveTo(4.67f, 4.0f, 5.5f, 4.0f)
                reflectiveCurveTo(7.0f, 4.67f, 7.0f, 5.5f)
                reflectiveCurveTo(6.33f, 7.0f, 5.5f, 7.0f)
                close()
            }
        }

        val Lyrics: ImageVector by glyph("Filled.Lyrics") {
            shape {
                moveTo(14.0f, 9.0f)
                curveToRelative(0.0f, -2.04f, 1.24f, -3.79f, 3.0f, -4.57f)
                verticalLineTo(4.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                horizontalLineTo(4.0f)
                curveTo(2.9f, 2.0f, 2.01f, 2.9f, 2.01f, 4.0f)
                lineTo(2.0f, 22.0f)
                lineToRelative(4.0f, -4.0f)
                horizontalLineToRelative(9.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineToRelative(-2.42f)
                curveTo(15.24f, 12.8f, 14.0f, 11.05f, 14.0f, 9.0f)
                close()
                moveTo(10.0f, 14.0f)
                horizontalLineTo(6.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(4.0f)
                verticalLineTo(14.0f)
                close()
                moveTo(13.0f, 11.0f)
                horizontalLineTo(6.0f)
                verticalLineTo(9.0f)
                horizontalLineToRelative(7.0f)
                verticalLineTo(11.0f)
                close()
                moveTo(13.0f, 8.0f)
                horizontalLineTo(6.0f)
                verticalLineTo(6.0f)
                horizontalLineToRelative(7.0f)
                verticalLineTo(8.0f)
                close()
            }
            shape {
                moveTo(20.0f, 6.18f)
                curveTo(19.69f, 6.07f, 19.35f, 6.0f, 19.0f, 6.0f)
                curveToRelative(-1.66f, 0.0f, -3.0f, 1.34f, -3.0f, 3.0f)
                curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
                reflectiveCurveToRelative(3.0f, -1.34f, 3.0f, -3.0f)
                verticalLineTo(3.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(1.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineTo(6.18f)
                close()
            }
        }

        val Mic: ImageVector by glyph("Filled.Mic") {
            shape {
                moveTo(12.0f, 14.0f)
                curveToRelative(1.66f, 0.0f, 2.99f, -1.34f, 2.99f, -3.0f)
                lineTo(15.0f, 5.0f)
                curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
                reflectiveCurveTo(9.0f, 3.34f, 9.0f, 5.0f)
                verticalLineToRelative(6.0f)
                curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
                close()
                moveTo(17.3f, 11.0f)
                curveToRelative(0.0f, 3.0f, -2.54f, 5.1f, -5.3f, 5.1f)
                reflectiveCurveTo(6.7f, 14.0f, 6.7f, 11.0f)
                lineTo(5.0f, 11.0f)
                curveToRelative(0.0f, 3.41f, 2.72f, 6.23f, 6.0f, 6.72f)
                lineTo(11.0f, 21.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-3.28f)
                curveToRelative(3.28f, -0.48f, 6.0f, -3.3f, 6.0f, -6.72f)
                horizontalLineToRelative(-1.7f)
                close()
            }
        }

        val MoreVert: ImageVector by glyph("Filled.MoreVert") {
            shape {
                moveTo(12.0f, 8.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                reflectiveCurveToRelative(-2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                close()
                moveTo(12.0f, 10.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(12.0f, 16.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                close()
            }
        }

        val MusicNote: ImageVector by glyph("Filled.MusicNote") {
            shape {
                moveTo(12.0f, 3.0f)
                verticalLineToRelative(10.55f)
                curveToRelative(-0.59f, -0.34f, -1.27f, -0.55f, -2.0f, -0.55f)
                curveToRelative(-2.21f, 0.0f, -4.0f, 1.79f, -4.0f, 4.0f)
                reflectiveCurveToRelative(1.79f, 4.0f, 4.0f, 4.0f)
                reflectiveCurveToRelative(4.0f, -1.79f, 4.0f, -4.0f)
                verticalLineTo(7.0f)
                horizontalLineToRelative(4.0f)
                verticalLineTo(3.0f)
                horizontalLineToRelative(-6.0f)
                close()
            }
        }

        val Pause: ImageVector by glyph("Filled.Pause") {
            shape {
                moveTo(6.0f, 19.0f)
                horizontalLineToRelative(4.0f)
                lineTo(10.0f, 5.0f)
                lineTo(6.0f, 5.0f)
                verticalLineToRelative(14.0f)
                close()
                moveTo(14.0f, 5.0f)
                verticalLineToRelative(14.0f)
                horizontalLineToRelative(4.0f)
                lineTo(18.0f, 5.0f)
                horizontalLineToRelative(-4.0f)
                close()
            }
        }

        val PlayArrow: ImageVector by glyph("Filled.PlayArrow") {
            shape {
                moveTo(8.0f, 5.0f)
                verticalLineToRelative(14.0f)
                lineToRelative(11.0f, -7.0f)
                close()
            }
        }

        val RadioButtonUnchecked: ImageVector by glyph("Filled.RadioButtonUnchecked") {
            shape {
                moveTo(12.0f, 2.0f)
                curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
                reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
                close()
                moveTo(12.0f, 20.0f)
                curveToRelative(-4.42f, 0.0f, -8.0f, -3.58f, -8.0f, -8.0f)
                reflectiveCurveToRelative(3.58f, -8.0f, 8.0f, -8.0f)
                reflectiveCurveToRelative(8.0f, 3.58f, 8.0f, 8.0f)
                reflectiveCurveToRelative(-3.58f, 8.0f, -8.0f, 8.0f)
                close()
            }
        }

        val Refresh: ImageVector by glyph("Filled.Refresh") {
            shape {
                moveTo(17.65f, 6.35f)
                curveTo(16.2f, 4.9f, 14.21f, 4.0f, 12.0f, 4.0f)
                curveToRelative(-4.42f, 0.0f, -7.99f, 3.58f, -7.99f, 8.0f)
                reflectiveCurveToRelative(3.57f, 8.0f, 7.99f, 8.0f)
                curveToRelative(3.73f, 0.0f, 6.84f, -2.55f, 7.73f, -6.0f)
                horizontalLineToRelative(-2.08f)
                curveToRelative(-0.82f, 2.33f, -3.04f, 4.0f, -5.65f, 4.0f)
                curveToRelative(-3.31f, 0.0f, -6.0f, -2.69f, -6.0f, -6.0f)
                reflectiveCurveToRelative(2.69f, -6.0f, 6.0f, -6.0f)
                curveToRelative(1.66f, 0.0f, 3.14f, 0.69f, 4.22f, 1.78f)
                lineTo(13.0f, 11.0f)
                horizontalLineToRelative(7.0f)
                verticalLineTo(4.0f)
                lineToRelative(-2.35f, 2.35f)
                close()
            }
        }

        val Repeat: ImageVector by glyph("Filled.Repeat") {
            shape {
                moveTo(7.0f, 7.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(3.0f)
                lineToRelative(4.0f, -4.0f)
                lineToRelative(-4.0f, -4.0f)
                verticalLineToRelative(3.0f)
                lineTo(5.0f, 5.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(2.0f)
                lineTo(7.0f, 7.0f)
                close()
                moveTo(17.0f, 17.0f)
                lineTo(7.0f, 17.0f)
                verticalLineToRelative(-3.0f)
                lineToRelative(-4.0f, 4.0f)
                lineToRelative(4.0f, 4.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(-6.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(4.0f)
                close()
            }
        }

        val RepeatOne: ImageVector by glyph("Filled.RepeatOne") {
            shape {
                moveTo(7.0f, 7.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(3.0f)
                lineToRelative(4.0f, -4.0f)
                lineToRelative(-4.0f, -4.0f)
                verticalLineToRelative(3.0f)
                lineTo(5.0f, 5.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(2.0f)
                lineTo(7.0f, 7.0f)
                close()
                moveTo(17.0f, 17.0f)
                lineTo(7.0f, 17.0f)
                verticalLineToRelative(-3.0f)
                lineToRelative(-4.0f, 4.0f)
                lineToRelative(4.0f, 4.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(-6.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(13.0f, 15.0f)
                lineTo(13.0f, 9.0f)
                horizontalLineToRelative(-1.0f)
                lineToRelative(-2.0f, 1.0f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(1.5f)
                verticalLineToRelative(4.0f)
                lineTo(13.0f, 15.0f)
                close()
            }
        }

        val Search: ImageVector by glyph("Filled.Search") {
            shape {
                moveTo(15.5f, 14.0f)
                horizontalLineToRelative(-0.79f)
                lineToRelative(-0.28f, -0.27f)
                curveTo(15.41f, 12.59f, 16.0f, 11.11f, 16.0f, 9.5f)
                curveTo(16.0f, 5.91f, 13.09f, 3.0f, 9.5f, 3.0f)
                reflectiveCurveTo(3.0f, 5.91f, 3.0f, 9.5f)
                reflectiveCurveTo(5.91f, 16.0f, 9.5f, 16.0f)
                curveToRelative(1.61f, 0.0f, 3.09f, -0.59f, 4.23f, -1.57f)
                lineToRelative(0.27f, 0.28f)
                verticalLineToRelative(0.79f)
                lineToRelative(5.0f, 4.99f)
                lineTo(20.49f, 19.0f)
                lineToRelative(-4.99f, -5.0f)
                close()
                moveTo(9.5f, 14.0f)
                curveTo(7.01f, 14.0f, 5.0f, 11.99f, 5.0f, 9.5f)
                reflectiveCurveTo(7.01f, 5.0f, 9.5f, 5.0f)
                reflectiveCurveTo(14.0f, 7.01f, 14.0f, 9.5f)
                reflectiveCurveTo(11.99f, 14.0f, 9.5f, 14.0f)
                close()
            }
        }

        val Settings: ImageVector by glyph("Filled.Settings") {
            shape {
                moveTo(19.14f, 12.94f)
                curveToRelative(0.04f, -0.3f, 0.06f, -0.61f, 0.06f, -0.94f)
                curveToRelative(0.0f, -0.32f, -0.02f, -0.64f, -0.07f, -0.94f)
                lineToRelative(2.03f, -1.58f)
                curveToRelative(0.18f, -0.14f, 0.23f, -0.41f, 0.12f, -0.61f)
                lineToRelative(-1.92f, -3.32f)
                curveToRelative(-0.12f, -0.22f, -0.37f, -0.29f, -0.59f, -0.22f)
                lineToRelative(-2.39f, 0.96f)
                curveToRelative(-0.5f, -0.38f, -1.03f, -0.7f, -1.62f, -0.94f)
                lineTo(14.4f, 2.81f)
                curveToRelative(-0.04f, -0.24f, -0.24f, -0.41f, -0.48f, -0.41f)
                horizontalLineToRelative(-3.84f)
                curveToRelative(-0.24f, 0.0f, -0.43f, 0.17f, -0.47f, 0.41f)
                lineTo(9.25f, 5.35f)
                curveTo(8.66f, 5.59f, 8.12f, 5.92f, 7.63f, 6.29f)
                lineTo(5.24f, 5.33f)
                curveToRelative(-0.22f, -0.08f, -0.47f, 0.0f, -0.59f, 0.22f)
                lineTo(2.74f, 8.87f)
                curveTo(2.62f, 9.08f, 2.66f, 9.34f, 2.86f, 9.48f)
                lineToRelative(2.03f, 1.58f)
                curveTo(4.84f, 11.36f, 4.8f, 11.69f, 4.8f, 12.0f)
                reflectiveCurveToRelative(0.02f, 0.64f, 0.07f, 0.94f)
                lineToRelative(-2.03f, 1.58f)
                curveToRelative(-0.18f, 0.14f, -0.23f, 0.41f, -0.12f, 0.61f)
                lineToRelative(1.92f, 3.32f)
                curveToRelative(0.12f, 0.22f, 0.37f, 0.29f, 0.59f, 0.22f)
                lineToRelative(2.39f, -0.96f)
                curveToRelative(0.5f, 0.38f, 1.03f, 0.7f, 1.62f, 0.94f)
                lineToRelative(0.36f, 2.54f)
                curveToRelative(0.05f, 0.24f, 0.24f, 0.41f, 0.48f, 0.41f)
                horizontalLineToRelative(3.84f)
                curveToRelative(0.24f, 0.0f, 0.44f, -0.17f, 0.47f, -0.41f)
                lineToRelative(0.36f, -2.54f)
                curveToRelative(0.59f, -0.24f, 1.13f, -0.56f, 1.62f, -0.94f)
                lineToRelative(2.39f, 0.96f)
                curveToRelative(0.22f, 0.08f, 0.47f, 0.0f, 0.59f, -0.22f)
                lineToRelative(1.92f, -3.32f)
                curveToRelative(0.12f, -0.22f, 0.07f, -0.47f, -0.12f, -0.61f)
                lineTo(19.14f, 12.94f)
                close()
                moveTo(12.0f, 15.6f)
                curveToRelative(-1.98f, 0.0f, -3.6f, -1.62f, -3.6f, -3.6f)
                reflectiveCurveToRelative(1.62f, -3.6f, 3.6f, -3.6f)
                reflectiveCurveToRelative(3.6f, 1.62f, 3.6f, 3.6f)
                reflectiveCurveTo(13.98f, 15.6f, 12.0f, 15.6f)
                close()
            }
        }

        val Shuffle: ImageVector by glyph("Filled.Shuffle") {
            shape {
                moveTo(10.59f, 9.17f)
                lineTo(5.41f, 4.0f)
                lineTo(4.0f, 5.41f)
                lineToRelative(5.17f, 5.17f)
                lineToRelative(1.42f, -1.41f)
                close()
                moveTo(14.5f, 4.0f)
                lineToRelative(2.04f, 2.04f)
                lineTo(4.0f, 18.59f)
                lineTo(5.41f, 20.0f)
                lineTo(17.96f, 7.46f)
                lineTo(20.0f, 9.5f)
                lineTo(20.0f, 4.0f)
                horizontalLineToRelative(-5.5f)
                close()
                moveTo(14.83f, 13.41f)
                lineToRelative(-1.41f, 1.41f)
                lineToRelative(3.13f, 3.13f)
                lineTo(14.5f, 20.0f)
                lineTo(20.0f, 20.0f)
                verticalLineToRelative(-5.5f)
                lineToRelative(-2.04f, 2.04f)
                lineToRelative(-3.13f, -3.13f)
                close()
            }
        }

        val Smartphone: ImageVector by glyph("Filled.Smartphone") {
            shape {
                moveTo(17.0f, 1.01f)
                lineTo(7.0f, 1.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(18.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(10.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(3.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -1.99f, -2.0f, -1.99f)
                close()
                moveTo(17.0f, 19.0f)
                horizontalLineTo(7.0f)
                verticalLineTo(5.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(14.0f)
                close()
            }
        }

        val Speaker: ImageVector by glyph("Filled.Speaker") {
            shape {
                moveTo(17.0f, 2.0f)
                lineTo(7.0f, 2.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(16.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 1.99f, 2.0f, 1.99f)
                lineTo(17.0f, 22.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(19.0f, 4.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(12.0f, 4.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, 0.9f, 2.0f, 2.0f)
                reflectiveCurveToRelative(-0.9f, 2.0f, -2.0f, 2.0f)
                curveToRelative(-1.11f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                reflectiveCurveToRelative(0.89f, -2.0f, 2.0f, -2.0f)
                close()
                moveTo(12.0f, 20.0f)
                curveToRelative(-2.76f, 0.0f, -5.0f, -2.24f, -5.0f, -5.0f)
                reflectiveCurveToRelative(2.24f, -5.0f, 5.0f, -5.0f)
                reflectiveCurveToRelative(5.0f, 2.24f, 5.0f, 5.0f)
                reflectiveCurveToRelative(-2.24f, 5.0f, -5.0f, 5.0f)
                close()
                moveTo(12.0f, 12.0f)
                curveToRelative(-1.66f, 0.0f, -3.0f, 1.34f, -3.0f, 3.0f)
                reflectiveCurveToRelative(1.34f, 3.0f, 3.0f, 3.0f)
                reflectiveCurveToRelative(3.0f, -1.34f, 3.0f, -3.0f)
                reflectiveCurveToRelative(-1.34f, -3.0f, -3.0f, -3.0f)
                close()
            }
        }

        val Whatshot: ImageVector by glyph("Filled.Whatshot") {
            shape {
                moveTo(13.5f, 0.67f)
                reflectiveCurveToRelative(0.74f, 2.65f, 0.74f, 4.8f)
                curveToRelative(0.0f, 2.06f, -1.35f, 3.73f, -3.41f, 3.73f)
                curveToRelative(-2.07f, 0.0f, -3.63f, -1.67f, -3.63f, -3.73f)
                lineToRelative(0.03f, -0.36f)
                curveTo(5.21f, 7.51f, 4.0f, 10.62f, 4.0f, 14.0f)
                curveToRelative(0.0f, 4.42f, 3.58f, 8.0f, 8.0f, 8.0f)
                reflectiveCurveToRelative(8.0f, -3.58f, 8.0f, -8.0f)
                curveTo(20.0f, 8.61f, 17.41f, 3.8f, 13.5f, 0.67f)
                close()
                moveTo(11.71f, 19.0f)
                curveToRelative(-1.78f, 0.0f, -3.22f, -1.4f, -3.22f, -3.14f)
                curveToRelative(0.0f, -1.62f, 1.05f, -2.76f, 2.81f, -3.12f)
                curveToRelative(1.77f, -0.36f, 3.6f, -1.21f, 4.62f, -2.58f)
                curveToRelative(0.39f, 1.29f, 0.59f, 2.65f, 0.59f, 4.04f)
                curveToRelative(0.0f, 2.65f, -2.15f, 4.8f, -4.8f, 4.8f)
                close()
            }
        }
    }

    object AutoMirrored {
        object Filled {
            val FormatListBulleted: ImageVector by glyph("AutoMirrored.Filled.FormatListBulleted", autoMirror = true) {
                shape {
                    moveTo(4.0f, 10.5f)
                    curveToRelative(-0.83f, 0.0f, -1.5f, 0.67f, -1.5f, 1.5f)
                    reflectiveCurveToRelative(0.67f, 1.5f, 1.5f, 1.5f)
                    reflectiveCurveToRelative(1.5f, -0.67f, 1.5f, -1.5f)
                    reflectiveCurveToRelative(-0.67f, -1.5f, -1.5f, -1.5f)
                    close()
                    moveTo(4.0f, 4.5f)
                    curveToRelative(-0.83f, 0.0f, -1.5f, 0.67f, -1.5f, 1.5f)
                    reflectiveCurveTo(3.17f, 7.5f, 4.0f, 7.5f)
                    reflectiveCurveTo(5.5f, 6.83f, 5.5f, 6.0f)
                    reflectiveCurveTo(4.83f, 4.5f, 4.0f, 4.5f)
                    close()
                    moveTo(4.0f, 16.5f)
                    curveToRelative(-0.83f, 0.0f, -1.5f, 0.68f, -1.5f, 1.5f)
                    reflectiveCurveToRelative(0.68f, 1.5f, 1.5f, 1.5f)
                    reflectiveCurveToRelative(1.5f, -0.68f, 1.5f, -1.5f)
                    reflectiveCurveToRelative(-0.67f, -1.5f, -1.5f, -1.5f)
                    close()
                    moveTo(7.0f, 19.0f)
                    horizontalLineToRelative(14.0f)
                    verticalLineToRelative(-2.0f)
                    lineTo(7.0f, 17.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(7.0f, 13.0f)
                    horizontalLineToRelative(14.0f)
                    verticalLineToRelative(-2.0f)
                    lineTo(7.0f, 11.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(7.0f, 5.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(14.0f)
                    lineTo(21.0f, 5.0f)
                    lineTo(7.0f, 5.0f)
                    close()
                }
            }

            val PlaylistPlay: ImageVector by glyph("AutoMirrored.Filled.PlaylistPlay", autoMirror = true) {
                shape {
                    moveTo(3.0f, 10.0f)
                    horizontalLineToRelative(11.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(-11.0f)
                    close()
                }
                shape {
                    moveTo(3.0f, 6.0f)
                    horizontalLineToRelative(11.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(-11.0f)
                    close()
                }
                shape {
                    moveTo(3.0f, 14.0f)
                    horizontalLineToRelative(7.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(-7.0f)
                    close()
                }
                shape {
                    moveTo(16.0f, 13.0f)
                    lineToRelative(0.0f, 8.0f)
                    lineToRelative(6.0f, -4.0f)
                    close()
                }
            }

            val QueueMusic: ImageVector by glyph("AutoMirrored.Filled.QueueMusic", autoMirror = true) {
                shape {
                    moveTo(15.0f, 6.0f)
                    horizontalLineTo(3.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(12.0f)
                    verticalLineTo(6.0f)
                    close()
                    moveTo(15.0f, 10.0f)
                    horizontalLineTo(3.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(12.0f)
                    verticalLineTo(10.0f)
                    close()
                    moveTo(3.0f, 16.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineTo(3.0f)
                    verticalLineTo(16.0f)
                    close()
                    moveTo(17.0f, 6.0f)
                    verticalLineToRelative(8.18f)
                    curveTo(16.69f, 14.07f, 16.35f, 14.0f, 16.0f, 14.0f)
                    curveToRelative(-1.66f, 0.0f, -3.0f, 1.34f, -3.0f, 3.0f)
                    reflectiveCurveToRelative(1.34f, 3.0f, 3.0f, 3.0f)
                    reflectiveCurveToRelative(3.0f, -1.34f, 3.0f, -3.0f)
                    verticalLineTo(8.0f)
                    horizontalLineToRelative(3.0f)
                    verticalLineTo(6.0f)
                    horizontalLineTo(17.0f)
                    close()
                }
            }

            val Sort: ImageVector by glyph("AutoMirrored.Filled.Sort", autoMirror = true) {
                shape {
                    moveTo(3.0f, 18.0f)
                    horizontalLineToRelative(6.0f)
                    verticalLineToRelative(-2.0f)
                    lineTo(3.0f, 16.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(3.0f, 6.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(18.0f)
                    lineTo(21.0f, 6.0f)
                    lineTo(3.0f, 6.0f)
                    close()
                    moveTo(3.0f, 13.0f)
                    horizontalLineToRelative(12.0f)
                    verticalLineToRelative(-2.0f)
                    lineTo(3.0f, 11.0f)
                    verticalLineToRelative(2.0f)
                    close()
                }
            }

            val VolumeDown: ImageVector by glyph("AutoMirrored.Filled.VolumeDown", autoMirror = true) {
                shape {
                    moveTo(18.5f, 12.0f)
                    curveToRelative(0.0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
                    verticalLineToRelative(8.05f)
                    curveToRelative(1.48f, -0.73f, 2.5f, -2.25f, 2.5f, -4.02f)
                    close()
                    moveTo(5.0f, 9.0f)
                    verticalLineToRelative(6.0f)
                    horizontalLineToRelative(4.0f)
                    lineToRelative(5.0f, 5.0f)
                    verticalLineTo(4.0f)
                    lineTo(9.0f, 9.0f)
                    horizontalLineTo(5.0f)
                    close()
                }
            }

            val VolumeUp: ImageVector by glyph("AutoMirrored.Filled.VolumeUp", autoMirror = true) {
                shape {
                    moveTo(3.0f, 9.0f)
                    verticalLineToRelative(6.0f)
                    horizontalLineToRelative(4.0f)
                    lineToRelative(5.0f, 5.0f)
                    lineTo(12.0f, 4.0f)
                    lineTo(7.0f, 9.0f)
                    lineTo(3.0f, 9.0f)
                    close()
                    moveTo(16.5f, 12.0f)
                    curveToRelative(0.0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
                    verticalLineToRelative(8.05f)
                    curveToRelative(1.48f, -0.73f, 2.5f, -2.25f, 2.5f, -4.02f)
                    close()
                    moveTo(14.0f, 3.23f)
                    verticalLineToRelative(2.06f)
                    curveToRelative(2.89f, 0.86f, 5.0f, 3.54f, 5.0f, 6.71f)
                    reflectiveCurveToRelative(-2.11f, 5.85f, -5.0f, 6.71f)
                    verticalLineToRelative(2.06f)
                    curveToRelative(4.01f, -0.91f, 7.0f, -4.49f, 7.0f, -8.77f)
                    reflectiveCurveToRelative(-2.99f, -7.86f, -7.0f, -8.77f)
                    close()
                }
            }
        }
    }
}

/**
 * Built on first use and kept, as Material's own are. The bounds and path
 * attributes are the ones Material's `materialIcon` and `materialPath` use,
 * restated here because those helpers live in material-icons-core, and nothing
 * the SDK brings in depends on that — material3 1.4 doesn't.
 */
private fun glyph(
    name: String,
    autoMirror: Boolean = false,
    shapes: ImageVector.Builder.() -> Unit,
): Lazy<ImageVector> = lazy {
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    ).apply(shapes).build()
}

private fun ImageVector.Builder.shape(pathBuilder: PathBuilder.() -> Unit) = path(
    fill = SolidColor(Color.Black),
    fillAlpha = 1f,
    stroke = null,
    strokeAlpha = 1f,
    strokeLineWidth = 1f,
    strokeLineCap = StrokeCap.Butt,
    strokeLineJoin = StrokeJoin.Bevel,
    strokeLineMiter = 1f,
    pathFillType = PathFillType.NonZero,
    pathBuilder = pathBuilder,
)
