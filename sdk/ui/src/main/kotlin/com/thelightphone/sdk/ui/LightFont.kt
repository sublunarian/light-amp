package com.thelightphone.sdk.ui

import android.content.Context
import android.graphics.fonts.SystemFonts
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

fun lightFontFamily(context: Context): FontFamily {
    // LP3s ship with Akkurat, use bundled versions
    systemAkkuratFonts()?.let { return it }
    // Can build with akkurat locally if you have the files, BUT FOLLOW LICENSE!!
    bundledAkkuratFonts(context)?.let { return it }
    return FontFamily.Default
}

private fun systemAkkuratFonts(): FontFamily? {
    val fonts = SystemFonts.getAvailableFonts()
        .filter { it.file?.name?.startsWith("Akkurat", ignoreCase = true) == true }
        .mapNotNull { font ->
            val file = font.file ?: return@mapNotNull null
            val weight = FontWeight(font.style.weight)
            val style = if (font.style.slant != 0) FontStyle.Italic else FontStyle.Normal
            Font(file = file, weight = weight, style = style)
        }
    return if (fonts.isNotEmpty()) FontFamily(fonts) else null
}

private fun bundledAkkuratFonts(context: Context): FontFamily? {
    val res = context.resources
    val pkg = context.packageName
    fun fontId(name: String): Int = res.getIdentifier(name, "font", pkg)

    val fonts = buildList {
        fontId("akkuratll_light").takeIf { it != 0 }
            ?.let { add(Font(it, FontWeight.Light)) }
        fontId("akkuratll_regular").takeIf { it != 0 }
            ?.let { add(Font(it, FontWeight.Normal)) }
        fontId("akkuratll_medium").takeIf { it != 0 }
            ?.let { add(Font(it, FontWeight.Medium)) }
        fontId("akkuratll_bold").takeIf { it != 0 }
            ?.let { add(Font(it, FontWeight.Bold)) }
    }
    return if (fonts.isNotEmpty()) FontFamily(fonts) else null
}
