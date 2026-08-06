package com.sublunar.amp.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design scaling ported from the original app so layouts match pixel-for-pixel.
 *
 * - [n]  density-scaled design unit. On the LP3 (3x) this is `size * 0.85`.
 * - [px] exact physical pixels on the LP3 (`physical / 3` dp).
 */
fun n(size: Number): Dp = (size.toFloat() * 0.85f).dp

fun px(physical: Number): Dp = (physical.toFloat() / 3f).dp

fun nSp(size: Number): TextUnit = (size.toFloat() * 0.85f).sp

/**
 * Type sized in physical pixels, like [px] is for layout.
 *
 * [nSp] multiplies by 0.85, so all but a few of its values land on fractions of
 * a pixel — nSp(16) is 40.8px — and a glyph whose em box falls between pixels is
 * hinted onto them differently line by line, which is what makes small text look
 * soft. Asking for the pixel height directly keeps every stem on the grid.
 */
fun pxSp(physical: Number): TextUnit = (physical.toFloat() / 3f).sp
