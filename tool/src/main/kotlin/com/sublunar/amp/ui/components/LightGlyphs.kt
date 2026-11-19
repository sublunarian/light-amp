package com.sublunar.amp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.ui.LightIcons

/** A rating as five of Light's stars, filled up to [stars]. */
@Composable
fun RatingStars(stars: Int) {
    val filled = ImageVector.vectorResource(LightIcons.STAR.drawableResource)
    val outline = ImageVector.vectorResource(LightIcons.STAR_OUTLINE.drawableResource)
    Row(horizontalArrangement = Arrangement.spacedBy(px(RATING_STAR_GAP_PX))) {
        repeat(5) { i -> AppIcon(if (i < stars) filled else outline, size = px(RATING_STAR_PX)) }
    }
}

/** About the height of the "★" it stands in for. */
private const val RATING_STAR_PX = 40
private const val RATING_STAR_GAP_PX = 10

/**
 * Light's arrow pointing up. The SDK has only the down one, `ic_arrow_down_white`,
 * which is these strokes turned over; drawn as they are, they point up.
 */
val LightArrowUp: ImageVector by lazy {
    ImageVector.Builder(
        name = "LightArrowUp",
        defaultWidth = 60.dp,
        defaultHeight = 60.dp,
        viewportWidth = 60f,
        viewportHeight = 60f,
    ).apply {
        ARROW_STROKES.forEach { d ->
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
}

private val ARROW_STROKES = listOf(
    "M 38.0,21.21 L 30.25,13.46 L 22.0,21.71",
    "M 30.25,13.46 L 30.25,46.54",
)
