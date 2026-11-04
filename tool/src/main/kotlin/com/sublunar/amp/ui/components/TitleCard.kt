package com.sublunar.amp.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.sublunar.amp.ui.pxSp

/**
 * The two-line title card in the player's header, shared so a screen showing the
 * same track looks exactly like the player. Sizes are physical pixels in multiples
 * of 3, so they are whole dp as well.
 */
@Composable
fun TitleCard(top: String, bottom: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TitleLine(top, TITLE_PX, TITLE_LINE_PX, dim = false)
        TitleLine(bottom, SUB_PX, SUB_LINE_PX, dim = true)
    }
}

@Composable
private fun TitleLine(text: String, sizePx: Int, linePx: Int, dim: Boolean) {
    AppText(
        text,
        pxSp(sizePx),
        lineHeight = pxSp(linePx),
        role = TextRole.Subheading,
        // Regular on both lines, whatever the role's own weight.
        weight = FontWeight.Normal,
        dim = dim,
        maxLines = 1,
        align = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().basicMarquee(),
        exact = true,
    )
}

/**
 * 48px over 38px (the card's 4:5), each on this font's natural line, about 1.21
 * times the size, keeping the card's own subheading tracking rather than snapping.
 */
private const val TITLE_PX = 48
private const val TITLE_LINE_PX = 58
private const val SUB_PX = 38
private const val SUB_LINE_PX = 46
