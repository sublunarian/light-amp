package com.sublunar.amp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sublunar.amp.ui.PlayerTheme
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarButton
import com.thelightphone.sdk.ui.LightTopBarCenter

/**
 * Standard screen chrome: themed background, a [LightTopBar] (optional back +
 * title + right action), and the screen's body below it.
 */
@Composable
fun Screen(
    onBack: (() -> Unit)? = null,
    title: String? = null,
    subtitle: String? = null,
    rightButton: LightTopBarButton? = null,
    leftButton: LightTopBarButton? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PlayerTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = leftButton
                    ?: onBack?.let { LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = it) },
                center = when {
                    title != null && subtitle != null -> LightTopBarCenter.TwoLineDetail(title, subtitle)
                    title != null -> LightTopBarCenter.Text(title)
                    else -> null
                },
                rightButton = rightButton,
            )
            content()
        }
    }
}
