package com.thelightphone.sdk.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide theme state for Light SDK tools.
 *
 * Wrap your tool UI in [LightTheme] and read [colors] so all screens share the same palette.
 */
object LightThemeController {
    private val _colors = MutableStateFlow(LightThemeColors.Dark)
    val colors: StateFlow<LightColors> = _colors.asStateFlow()

    fun setDarkTheme() {
        _colors.value = LightThemeColors.Dark
    }

    fun setLightTheme() {
        _colors.value = LightThemeColors.Light
    }

    fun toggle() {
        _colors.value = if (_colors.value == LightThemeColors.Dark) {
            LightThemeColors.Light
        } else {
            LightThemeColors.Dark
        }
    }

    val isDarkTheme: Boolean
        get() = _colors.value == LightThemeColors.Dark
}
