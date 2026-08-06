package com.thelightphone.sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview

private const val UNDERLINE_THICKNESS_PX = 3f
private const val UNDERLINE_WIDTH_FRACTION = 0.8f

private const val VALUE_TO_UNDERLINE_GAP_GRID_UNITS = 0.5f

/**
 * Read-only field used in conjunction with LightTextInputEditor to show a value that can be edited by tapping and opening the editor
 * See Directions tool in LightOS for an example of this pattern in use
 */
@Composable
fun LightTextField(
    label: String,
    value: String,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LightThemeTokens.colors
    Column(modifier = modifier.fillMaxWidth()) {
        LightText(
            text = label,
            variant = LightTextVariant.Detail,
            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
        )
        val isPlaceholder = value.isBlank()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onClick)
                .padding(top = 0.25f.gridUnitsAsDp()),
        ) {
            LightText(
                text = if (isPlaceholder) placeholder else value,
                variant = LightTextVariant.Copy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(VALUE_TO_UNDERLINE_GAP_GRID_UNITS.gridUnitsAsDp()))
            Spacer(
                modifier = Modifier
                    .fillMaxWidth(UNDERLINE_WIDTH_FRACTION)
                    .height(UNDERLINE_THICKNESS_PX.designVerticalPxToDp())
                    .background(colors.content),
            )
        }
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewLightTextFieldEmptyDark() {
    LightTheme(colors = LightThemeColors.Dark) {
        LightTextField(
            label = "Name:",
            value = "",
            placeholder = "Your name",
            onClick = {},
            modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
        )
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewLightTextFieldFilledLight() {
    LightTheme(colors = LightThemeColors.Light) {
        LightTextField(
            label = "Name:",
            value = "Alex",
            placeholder = "Your name",
            onClick = {},
            modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
        )
    }
}
