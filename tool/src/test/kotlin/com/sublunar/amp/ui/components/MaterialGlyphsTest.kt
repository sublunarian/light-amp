package com.sublunar.amp.ui.components

import androidx.compose.material.icons.Icons as Material
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The glyphs vendored in MaterialGlyphs.kt, held against material-icons-extended.
 *
 * ImageVector compares by value — name, size, viewport, auto-mirroring, and every
 * path's nodes and attributes — so an equal glyph draws exactly what Material's
 * does. The mirroring is also checked on its own, because a glyph that stopped
 * flipping for right-to-left text would still look right in every English test.
 */
class MaterialGlyphsTest {

    @Test
    fun `every vendored glyph is the Material original`() {
        for ((material, vendored) in GLYPHS) {
            if (vendored == null) {
                fail("${material.name} is not vendored yet. Add to MaterialGlyphs.kt:\n\n${source(material)}")
            }
            assertEquals(material.autoMirror, vendored.autoMirror, "${material.name} auto-mirroring")
            assertEquals(material, vendored, "${material.name} differs from Material's, which is:\n\n${source(material)}")
        }
    }

    /** Kotlin for MaterialGlyphs.kt, from the library's own vector. */
    private fun source(vector: ImageVector): String {
        val indent = if (vector.name.startsWith("AutoMirrored.")) "            " else "        "
        val mirror = if (vector.autoMirror) ", autoMirror = true" else ""
        val out = StringBuilder()
        out.append("${indent}val ${vector.name.substringAfterLast('.')}: ImageVector by glyph(\"${vector.name}\"$mirror) {\n")
        for (node in vector.root) {
            check(node is VectorPath) { "${vector.name} has a group; glyph() only builds flat paths" }
            out.append("$indent    shape {\n")
            for (step in node.pathData) out.append("$indent        ${step.call()}\n")
            out.append("$indent    }\n")
        }
        return out.append("$indent}\n").toString()
    }

    /**
     * The PathBuilder call that adds this node. Floats print as Kotlin's shortest
     * round-tripping form, so the literal parses back to the very same value.
     */
    private fun PathNode.call(): String = when (this) {
        is PathNode.Close -> "close()"
        is PathNode.MoveTo -> "moveTo(${f(x)}, ${f(y)})"
        is PathNode.RelativeMoveTo -> "moveToRelative(${f(dx)}, ${f(dy)})"
        is PathNode.LineTo -> "lineTo(${f(x)}, ${f(y)})"
        is PathNode.RelativeLineTo -> "lineToRelative(${f(dx)}, ${f(dy)})"
        is PathNode.HorizontalTo -> "horizontalLineTo(${f(x)})"
        is PathNode.RelativeHorizontalTo -> "horizontalLineToRelative(${f(dx)})"
        is PathNode.VerticalTo -> "verticalLineTo(${f(y)})"
        is PathNode.RelativeVerticalTo -> "verticalLineToRelative(${f(dy)})"
        is PathNode.CurveTo -> "curveTo(${f(x1)}, ${f(y1)}, ${f(x2)}, ${f(y2)}, ${f(x3)}, ${f(y3)})"
        is PathNode.RelativeCurveTo ->
            "curveToRelative(${f(dx1)}, ${f(dy1)}, ${f(dx2)}, ${f(dy2)}, ${f(dx3)}, ${f(dy3)})"
        is PathNode.ReflectiveCurveTo -> "reflectiveCurveTo(${f(x1)}, ${f(y1)}, ${f(x2)}, ${f(y2)})"
        is PathNode.RelativeReflectiveCurveTo ->
            "reflectiveCurveToRelative(${f(dx1)}, ${f(dy1)}, ${f(dx2)}, ${f(dy2)})"
        is PathNode.QuadTo -> "quadTo(${f(x1)}, ${f(y1)}, ${f(x2)}, ${f(y2)})"
        is PathNode.RelativeQuadTo -> "quadToRelative(${f(dx1)}, ${f(dy1)}, ${f(dx2)}, ${f(dy2)})"
        is PathNode.ReflectiveQuadTo -> "reflectiveQuadTo(${f(x)}, ${f(y)})"
        is PathNode.RelativeReflectiveQuadTo -> "reflectiveQuadToRelative(${f(dx)}, ${f(dy)})"
        is PathNode.ArcTo ->
            "arcTo(${f(horizontalEllipseRadius)}, ${f(verticalEllipseRadius)}, ${f(theta)}, " +
                "$isMoreThanHalf, $isPositiveArc, ${f(arcStartX)}, ${f(arcStartY)})"
        is PathNode.RelativeArcTo ->
            "arcToRelative(${f(horizontalEllipseRadius)}, ${f(verticalEllipseRadius)}, ${f(theta)}, " +
                "$isMoreThanHalf, $isPositiveArc, ${f(arcStartDx)}, ${f(arcStartDy)})"
    }

    private fun f(value: Float): String = "${value}f"

    private companion object {
        /**
         * Material's glyph, and the vendored one. To add a glyph, pair it with
         * `null` here and run the test: it fails with the source to paste.
         */
        val GLYPHS: List<Pair<ImageVector, ImageVector?>> = listOf(
            Material.Filled.Add to Icons.Filled.Add,
            Material.Filled.AddCircleOutline to Icons.Filled.AddCircleOutline,
            Material.Filled.Album to Icons.Filled.Album,
            Material.Filled.ArrowDownward to Icons.Filled.ArrowDownward,
            Material.Filled.ArrowDropDown to Icons.Filled.ArrowDropDown,
            Material.Filled.ArrowUpward to Icons.Filled.ArrowUpward,
            Material.Filled.Bluetooth to Icons.Filled.Bluetooth,
            Material.Filled.Cast to Icons.Filled.Cast,
            Material.Filled.CheckCircle to Icons.Filled.CheckCircle,
            Material.Filled.Close to Icons.Filled.Close,
            Material.Filled.CloudOff to Icons.Filled.CloudOff,
            Material.Filled.Dehaze to Icons.Filled.Dehaze,
            Material.Filled.DeleteOutline to Icons.Filled.DeleteOutline,
            Material.Filled.Download to Icons.Filled.Download,
            Material.Filled.DownloadDone to Icons.Filled.DownloadDone,
            Material.Filled.FastForward to Icons.Filled.FastForward,
            Material.Filled.FastRewind to Icons.Filled.FastRewind,
            Material.Filled.Favorite to Icons.Filled.Favorite,
            Material.Filled.FavoriteBorder to Icons.Filled.FavoriteBorder,
            Material.Filled.FilterNone to Icons.Filled.FilterNone,
            Material.Filled.GraphicEq to Icons.Filled.GraphicEq,
            Material.Filled.HistoryEdu to Icons.Filled.HistoryEdu,
            Material.Filled.LibraryMusic to Icons.Filled.LibraryMusic,
            Material.Filled.LocalOffer to Icons.Filled.LocalOffer,
            Material.Filled.Lyrics to Icons.Filled.Lyrics,
            Material.Filled.Mic to Icons.Filled.Mic,
            Material.Filled.MoreVert to Icons.Filled.MoreVert,
            Material.Filled.MusicNote to Icons.Filled.MusicNote,
            Material.Filled.Pause to Icons.Filled.Pause,
            Material.Filled.PlayArrow to Icons.Filled.PlayArrow,
            Material.Filled.RadioButtonUnchecked to Icons.Filled.RadioButtonUnchecked,
            Material.Filled.Refresh to Icons.Filled.Refresh,
            Material.Filled.Repeat to Icons.Filled.Repeat,
            Material.Filled.RepeatOne to Icons.Filled.RepeatOne,
            Material.Filled.Search to Icons.Filled.Search,
            Material.Filled.Settings to Icons.Filled.Settings,
            Material.Filled.Shuffle to Icons.Filled.Shuffle,
            Material.Filled.Smartphone to Icons.Filled.Smartphone,
            Material.Filled.Speaker to Icons.Filled.Speaker,
            Material.Filled.Whatshot to Icons.Filled.Whatshot,
            Material.AutoMirrored.Filled.FormatListBulleted to Icons.AutoMirrored.Filled.FormatListBulleted,
            Material.AutoMirrored.Filled.PlaylistPlay to Icons.AutoMirrored.Filled.PlaylistPlay,
            Material.AutoMirrored.Filled.QueueMusic to Icons.AutoMirrored.Filled.QueueMusic,
            Material.AutoMirrored.Filled.Sort to Icons.AutoMirrored.Filled.Sort,
            Material.AutoMirrored.Filled.VolumeDown to Icons.AutoMirrored.Filled.VolumeDown,
            Material.AutoMirrored.Filled.VolumeUp to Icons.AutoMirrored.Filled.VolumeUp,
        )
    }
}
