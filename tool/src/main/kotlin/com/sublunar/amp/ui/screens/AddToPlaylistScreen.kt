package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.PlayAllRow
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.launch

class AddToPlaylistScreen(
    sealed: SealedLightActivity,
    /** One or many — a multi-selection adds in list order. */
    private val trackIds: List<String>,
) : SimpleLightScreen<Unit>(sealed) {

    constructor(sealed: SealedLightActivity, trackId: String) : this(sealed, listOf(trackId))

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val playlists by App.library.playlists.collectAsState()
        LaunchedEffect(Unit) { App.library.refreshPlaylists() }

        ListScreen(onBack = { goBack() }, title = "Add to Playlist") {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // First, and shown even with no playlists yet: "these songs are a
                // new playlist" is at least as common a reason to be here as
                // filing them into an existing one.
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
                    // The songs are part of the create rather than a second pass:
                    // a failed create then can't half-succeed, and Plex won't make
                    // a playlist any other way.
                    // Through the repository, so a local source writes an m3u8
                    // and a server source posts to the server.
                    App.library.createPlaylist(name, trackIds)
                }
                goBack(Unit)
            },
        )
    }

    private fun addTo(playlistId: String) {
        App.scope.launch {
            addSequentially(playlistId)
            App.library.refreshPlaylists()
        }
        // Unit rather than the default null: the result is how a caller tells
        // "added" from "backed out".
        goBack(Unit)
    }

    /**
     * Sequential rather than concurrent: updatePlaylist appends, so parallel
     * calls would race the order.
     */
    private suspend fun addSequentially(playlistId: String) {
        trackIds.forEach { App.library.addToPlaylist(playlistId, it) }
    }
}
