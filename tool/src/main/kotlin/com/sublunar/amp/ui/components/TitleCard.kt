package com.sublunar.amp.ui.components

import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightThemeColors
import androidx.compose.ui.graphics.Color
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
fun TitleCard(
    top: String,
    bottom: String,
    /** The second line in the SDK's own lighter grey, as the player has it. */
    sdkGrey: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TitleLine(top, TITLE_PX, TITLE_LINE_PX, dim = false)
        TitleLine(bottom, SUB_PX, SUB_LINE_PX, dim = true, colour = if (sdkGrey) LightThemeColors.Dark.contentSecondary else null)
    }
}

@Composable
private fun TitleLine(text: String, sizePx: Int, linePx: Int, dim: Boolean, colour: Color? = null) {
    AppText(
        text,
        pxSp(sizePx),
        lineHeight = pxSp(linePx),
        role = TextRole.Subheading,
        // Regular on both lines, whatever the role's own weight.
        weight = FontWeight.Normal,
        dim = dim && colour == null,
        color = colour ?: LightThemeTokens.colors.content,
        // Cut, not scrolled: nothing in LightOS moves, and Song Info has it whole.
        maxLines = 1,
        align = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
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
