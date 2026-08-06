package com.sublunar.amp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sublunar.amp.ui.PlayerTheme

/**
 * Screen chrome using the app's [AppHeader] (back + title, optional 2-line
 * subtitle and right action) followed by the body. Used by the utility screens
 * (search, settings, sort, actions) so they match the rest of the app.
 */
@Composable
fun ListScreen(
    onBack: () -> Unit,
    title: String,
    subtitle: String? = null,
    rightAction: HeaderAction? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PlayerTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            AppHeader(
                onBack = onBack,
                title = if (subtitle == null) title else null,
                // The same two-line card the player wears, so opening a sheet
                // from it doesn't restate the track at a slightly different size.
                titleContent = if (subtitle != null) {
                    { TitleCard(title, subtitle) }
                } else {
                    null
                },
                rightAction = rightAction,
            )
            content()
        }
    }
}

/**
 * The body of an action sheet: rows that scroll once there are more than fit.
 *
 * These menus are built from conditionals — a track that is in the queue, on a
 * server with playlists and ratings, and already downloaded shows several rows
 * more than one that isn't — so their height depends on the item. A plain
 * Column clips whatever falls past the bottom of the screen and gives no way to
 * reach it, which on the longest menus hides a real action.
 */
@Composable
fun ActionList(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        content = content,
    )
}
