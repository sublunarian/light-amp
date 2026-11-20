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
 * The Material icons Amp draws, vendored from `material-icons-extended`.
 *
 * That artifact is on Light's allow-list but missing from the offline Gradle cache
 * their store builder builds against (it holds only what the SDK's sample tool
 * resolves), so a store build can't resolve it. Only the glyphs Amp uses are here.
 *
 * The path data is Google's Material Icons (Apache License 2.0), printed out of the
 * library's own vectors, not redrawn. MaterialGlyphsTest holds every one against the
 * library. Names mirror Material's (`Icons.Filled.Album`). To add a glyph, list it in
 * MaterialGlyphsTest with `null` for the vendored side: the test fails with the
 * source to paste here.
 */
object Icons {
    object Filled {
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

        val Dns: ImageVector by glyph("Filled.Dns") {
            shape {
                moveTo(20.0f, 13.0f)
                horizontalLineTo(4.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(6.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-6.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                close()
                moveTo(7.0f, 19.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
                reflectiveCurveToRelative(-0.9f, 2.0f, -2.0f, 2.0f)
                close()
                moveTo(20.0f, 3.0f)
                horizontalLineTo(4.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(6.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineTo(4.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                close()
                moveTo(7.0f, 9.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
                reflectiveCurveToRelative(-0.9f, 2.0f, -2.0f, 2.0f)
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

        val Star: ImageVector by glyph("Filled.Star") {
            shape {
                moveTo(12.0f, 17.27f)
                lineTo(18.18f, 21.0f)
                lineToRelative(-1.64f, -7.03f)
                lineTo(22.0f, 9.24f)
                lineToRelative(-7.19f, -0.61f)
                lineTo(12.0f, 2.0f)
                lineTo(9.19f, 8.63f)
                lineTo(2.0f, 9.24f)
                lineToRelative(5.46f, 4.73f)
                lineTo(5.82f, 21.0f)
                close()
            }
        }

        val StarBorder: ImageVector by glyph("Filled.StarBorder") {
            shape {
                moveTo(22.0f, 9.24f)
                lineToRelative(-7.19f, -0.62f)
                lineTo(12.0f, 2.0f)
                lineTo(9.19f, 8.63f)
                lineTo(2.0f, 9.24f)
                lineToRelative(5.46f, 4.73f)
                lineTo(5.82f, 21.0f)
                lineTo(12.0f, 17.27f)
                lineTo(18.18f, 21.0f)
                lineToRelative(-1.63f, -7.03f)
                lineTo(22.0f, 9.24f)
                close()
                moveTo(12.0f, 15.4f)
                lineToRelative(-3.76f, 2.27f)
                lineToRelative(1.0f, -4.28f)
                lineToRelative(-3.32f, -2.88f)
                lineToRelative(4.38f, -0.38f)
                lineTo(12.0f, 6.1f)
                lineToRelative(1.71f, 4.04f)
                lineToRelative(4.38f, 0.38f)
                lineToRelative(-3.32f, 2.88f)
                lineToRelative(1.0f, 4.28f)
                lineTo(12.0f, 15.4f)
                close()
            }
        }
    }

    object AutoMirrored {
        object Filled {
            val Backspace: ImageVector by glyph("AutoMirrored.Filled.Backspace", autoMirror = true) {
                shape {
                    moveTo(22.0f, 3.0f)
                    lineTo(7.0f, 3.0f)
                    curveToRelative(-0.69f, 0.0f, -1.23f, 0.35f, -1.59f, 0.88f)
                    lineTo(0.0f, 12.0f)
                    lineToRelative(5.41f, 8.11f)
                    curveToRelative(0.36f, 0.53f, 0.9f, 0.89f, 1.59f, 0.89f)
                    horizontalLineToRelative(15.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    lineTo(24.0f, 5.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    close()
                    moveTo(19.0f, 15.59f)
                    lineTo(17.59f, 17.0f)
                    lineTo(14.0f, 13.41f)
                    lineTo(10.41f, 17.0f)
                    lineTo(9.0f, 15.59f)
                    lineTo(12.59f, 12.0f)
                    lineTo(9.0f, 8.41f)
                    lineTo(10.41f, 7.0f)
                    lineTo(14.0f, 10.59f)
                    lineTo(17.59f, 7.0f)
                    lineTo(19.0f, 8.41f)
                    lineTo(15.41f, 12.0f)
                    lineTo(19.0f, 15.59f)
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
 * Built on first use and kept, as Material's own are. The bounds and path attributes
 * restate Material's `materialIcon` and `materialPath`, which live in
 * material-icons-core, a library nothing the SDK brings in depends on.
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
