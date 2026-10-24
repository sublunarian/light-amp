package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import kotlinx.coroutines.launch

/**
 * How an album list is laid out: a column of titles, or a wall of covers. Opened
 * from More, and only while artwork is on, since the grid is nothing but artwork.
 */
class AlbumViewScreen(
    sealed: SealedLightActivity,
    /** Editing an artist's own page rather than the whole album list. */
    private val forArtist: Boolean = false,
) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val setting = if (forArtist) App.settings.artistAlbumGrid else App.settings.albumGrid
        val grid by setting.state.collectAsState()

        // Named, because the two pickers look identical and are separate settings.
        ListScreen(
            onBack = { goBack() },
            title = "View",
            subtitle = if (forArtist) "An artist's albums" else "All albums",
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TextRow(title = "List", checked = !grid) { choose(false) }
                TextRow(title = "Grid", checked = grid) { choose(true) }
            }
        }
    }

    private fun choose(grid: Boolean) {
        App.scope.launch {
            if (forArtist) App.settings.artistAlbumGrid.set(grid) else App.settings.albumGrid.set(grid)
        }
        // Reported, so the More that pushed this closes with it (see MoreScreen).
        goBack(Unit)
    }
}
