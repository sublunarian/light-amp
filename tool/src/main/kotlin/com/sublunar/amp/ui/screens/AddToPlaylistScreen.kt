package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.data.WriteResult
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.PlayAllRow
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import kotlinx.coroutines.launch

class AddToPlaylistScreen(
    sealed: SealedLightActivity,
    /** One or many: a multi-selection adds in list order. */
    private val trackIds: List<String>,
) : AmpScreen<Unit>(sealed) {

    constructor(sealed: SealedLightActivity, trackId: String) : this(sealed, listOf(trackId))

    @Composable
    override fun Content() {
        // Not the read-only ones: Plex refuses to add to a smart playlist.
        val playlists = App.playlists.allPlaylists.collectAsState().value.filterNot { it.readOnly }
        LaunchedEffect(Unit) { App.playlists.refreshPlaylists() }

        ListScreen(onBack = { goBack() }, title = "Add to Playlist") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                // First, and shown even with no playlists yet.
                item { PlayAllRow(AppIcons.Add, "New Playlist") { createAndAdd() } }
                items(playlists, key = { it.id }) { playlist ->
                    TextRow(title = playlist.name) { addTo(playlist.id) }
                }
            }
        }
    }

    private fun createAndAdd() {
        navigateTo<String?>(
            { TextEntryScreen(it, title = "New Playlist") },
            resultCallback = { name ->
                if (name.isNullOrBlank()) return@navigateTo
                App.scope.launch {
                    // The songs are part of the create, not a second pass: a failed
                    // create can't half-succeed, and Plex makes playlists no other way.
                    App.playlists.createPlaylist(name, trackIds)
                }
                goBack(Unit)
            },
        )
    }

    private fun addTo(playlistId: String) {
        App.scope.launch {
            addSequentially(playlistId)
            App.playlists.refreshPlaylists()
        }
        // Unit rather than the default null tells a caller "added" from "backed out".
        goBack(Unit)
    }

    /**
     * One at a time, since each call appends and parallel ones would race the order,
     * and stopped at the first failure (already reported), since the rest would land
     * out of order behind the gap.
     */
    private suspend fun addSequentially(playlistId: String) {
        for (id in trackIds) if (App.playlists.addToPlaylist(playlistId, id) != WriteResult.Saved) break
    }
}
