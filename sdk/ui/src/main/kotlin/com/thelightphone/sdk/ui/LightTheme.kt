package com.thelightphone.sdk.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.sp

fun LightColors.inferredSurfaceScheme(): LightSurfaceScheme =
    if (background.luminance() > 0.5f) LightSurfaceScheme.Light else LightSurfaceScheme.Dark

/**
 * A minimal design system inspired by LightOS.
 *
 * This is intentionally small: it provides token-level building blocks (typography + colors)
 * that higher-level components can build on.
 */

@Immutable
data class LightColors(
    val background: Color,
    val content: Color,
    val contentSecondary: Color,
)

@Immutable
data class LightTypography(
    val title: TextStyle,
    val subtitle: TextStyle,
    val heading: TextStyle,
    val subheading: TextStyle,
    val copy: TextStyle,
    val button: TextStyle,
    val paragraph: TextStyle,
    val paragraphWide: TextStyle,
    val detail: TextStyle,
    val fine: TextStyle,
    val superfine: TextStyle,
    val micro: TextStyle,
)

object LightThemeColors {
    val Dark = LightColors(
        background = Color.Black,
        content = Color.White,
        contentSecondary = Color(0xFFBBBBBB),
    )

    val Light = LightColors(
        background = Color.White,
        content = Color.Black,
        contentSecondary = Color(0xFF666666),
    )
}

private val DefaultColors = LightThemeColors.Dark

/**
 * These values mirror the LP3 table in `LightOS/src/style/index.ts` (unscaled).
 */
private fun buildDefaultTypography(fontFamily: FontFamily): LightTypography = LightTypography(
    title = TextStyle(
        fontSize = 115.sp,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Light,
        lineHeight = (115 * 1.10).sp,
    ),
    subtitle = TextStyle(
        fontSize = 52.sp,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        lineHeight = (52 * 1.20).sp,
    ),
    heading = TextStyle(
        fontSize = 38.sp,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        lineHeight = (38 * 1.35).sp,
    ),
    subheading = TextStyle(
        fontSize = 30.sp,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        letterSpacing = (30 * 0.03).sp,
        lineHeight = (30 * 1.25).sp,
    ),
    copy = TextStyle(
        fontSize = 30.sp,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        lineHeight = (30 * 1.50).sp,
    ),
    button = TextStyle(
        fontSize = 30.sp,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        letterSpacing = (30 * 0.15).sp,
        lineHeight = (30 * 1.10).sp,
    ),
    paragraph = TextStyle(
        fontSize = 24.5.sp,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        lineHeight = (24.5 * 1.25).sp,
    ),
    paragraphWide = TextStyle(
        fontSize = 25.sp,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        letterSpacing = (25 * 0.02).sp,
        lineHeight = (25 * 1.30).sp,
    ),
    detail = TextStyle(
        fontSize = 20.sp,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        lineHeight = (20 * 1.45).sp,
    ),
    fine = TextStyle(
        fontSize = 25.sp,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        letterSpacing = (25 * 0.03).sp,
        lineHeight = (25 * 1.15).sp,
    ),
    superfine = TextStyle(
        fontSize = 16.sp,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        lineHeight = (16 * 1.20).sp,
    ),
    micro = TextStyle(
        fontSize = 8.sp,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        lineHeight = (8 * 1.20).sp,
    ),
)

private val FallbackTypography = buildDefaultTypography(FontFamily.Default)

@Composable
fun rememberLightTypography(): LightTypography {
    val context = LocalContext.current
    return remember(context) { buildDefaultTypography(lightFontFamily(context)) }
}

val LocalLightColors = staticCompositionLocalOf { DefaultColors }
val LocalLightTypography = staticCompositionLocalOf { FallbackTypography }
val LocalLightSurfaceScheme = staticCompositionLocalOf { LightSurfaceScheme.Dark }

object LightThemeTokens {
    val colors: LightColors
        @Composable get() = LocalLightColors.current

    val typography: LightTypography
        @Composable get() = LocalLightTypography.current

    val surfaceScheme: LightSurfaceScheme
        @Composable get() = LocalLightSurfaceScheme.current
}

private fun LightColors.toMaterialColorScheme(surfaceScheme: LightSurfaceScheme): ColorScheme {
    // Use MaterialTheme as a base so apps can still interop with M3 components,
    // but prefer Light* primitives for consistent visuals.
    return when (surfaceScheme) {
        LightSurfaceScheme.Dark -> darkColorScheme(
            background = background,
            surface = background,
            onBackground = content,
            onSurface = content,
            primary = content,
            onPrimary = background,
            secondary = contentSecondary,
            onSecondary = background,
        )

        LightSurfaceScheme.Light -> lightColorScheme(
            background = background,
            surface = background,
            onBackground = content,
            onSurface = content,
            primary = content,
            onPrimary = background,
            secondary = contentSecondary,
            onSecondary = background,
        )
    }
}

@Composable
fun LightTheme(
    colors: LightColors = DefaultColors,
    typography: LightTypography = rememberLightTypography(),
    surfaceScheme: LightSurfaceScheme = colors.inferredSurfaceScheme(),
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalLightColors provides colors,
        LocalLightTypography provides typography,
        LocalLightSurfaceScheme provides surfaceScheme,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialColorScheme(surfaceScheme),
            content = content,
        )
    }
}

class LightColorsPreviewProvider : PreviewParameterProvider<LightColors> {
    override val values: Sequence<LightColors> = sequenceOf(
        LightThemeColors.Light,
        LightThemeColors.Dark
    )
}

