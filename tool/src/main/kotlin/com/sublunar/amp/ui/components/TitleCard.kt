package com.sublunar.amp.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
        dim = dim,
        maxLines = 1,
        align = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().basicMarquee(),
    )
}

private const val TITLE_PX = 45
private const val TITLE_LINE_PX = 54
private const val SUB_PX = 36
private const val SUB_LINE_PX = 42
