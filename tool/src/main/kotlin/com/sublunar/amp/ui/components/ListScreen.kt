package com.sublunar.amp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sublunar.amp.ui.PlayerTheme
import com.sublunar.amp.ui.px

/**
 * Screen chrome for the utility screens (settings, sort, action sheets): an
 * [AppHeader] with back and title, then the body.
 */
@Composable
fun ListScreen(
    onBack: () -> Unit,
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PlayerTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            AppHeader(
                onBack = onBack,
                title = if (subtitle == null) title else null,
                // With a subtitle, the player's own two-line card, so a sheet
                // opened from it doesn't restate the track at a different size.
                titleContent = if (subtitle != null) {
                    { TitleCard(title, subtitle) }
                } else {
                    null
                },
            )
            content()
        }
    }
}

/**
 * The body of an action sheet: rows that scroll once there are more than fit.
 * Sheets are built from conditionals, so the longest ones overflow the screen,
 * and a plain Column would hide the rows past the bottom.
 */
@Composable
fun ActionList(content: @Composable ColumnScope.() -> Unit) {
    val state = rememberScrollState()
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(state)
                .padding(end = px(SCROLLBAR_LANE_PX)),
            content = content,
        )
        // The lists' scroll bar, via rememberScrollTarget(ScrollState).
        ListScrollBar(rememberScrollTarget(state))
    }
}
