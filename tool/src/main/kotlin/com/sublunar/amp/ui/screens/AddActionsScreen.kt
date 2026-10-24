package com.sublunar.amp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.sublunar.amp.App
import com.sublunar.amp.ui.components.ActionList
import com.sublunar.amp.ui.components.EmptyState
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import kotlinx.coroutines.launch

/**
 * The player's "+" sheet: the ways of keeping the track that's playing (liked, in a
 * playlist, rated, on the device) and of keeping the queue as a playlist. The •••
 * menu is about the track itself.
 */
class AddActionsScreen(
    sealed: SealedLightActivity,
    private val trackId: String,
) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val source by App.source.collectAsState()
        val tracks by App.library.tracks.collectAsState()
        // Follows the player: it describes what is playing now, not what was
        // playing when the button was pressed.
        val playing by App.playback.currentTrack.collectAsState()
        val id = playing?.id ?: trackId
        val track = remember(tracks, playing, id) {
            tracks.firstOrNull { it.id == id } ?: playing?.takeIf { it.id == id }
        }
        val downloadedIds by App.library.downloadedTrackIds.collectAsState()
        val downloaded = id in downloadedIds
        val queue by App.playback.queue.collectAsState()
        val queueName by App.playback.queueName.collectAsState()

        ListScreen(onBack = { goBack() }, title = track?.title ?: "Track", subtitle = track?.artist) {
            if (track == null) {
                EmptyState("Track not found")
                return@ListScreen
            }
            ActionList {
                if (source.supportsLikes) LikeTrackRow(track)
                if (source.supportsRatings) RatingRow(track.id, track.title, track.rating, isAlbum = false)
                AddToPlaylistRow(listOf(track.id)) { goBack() }
                // How a song radio becomes something to come back to (or download):
                // you hear it, you decide. The queue's name ("Judith Radio") is
                // offered where it has one.
                if (queue.isNotEmpty()) {
                    TextRow(title = "Save Queue as Playlist") {
                        val ids = queue.map { it.id }
                        navigateTo<String?>(
                            {
                                TextEntryScreen(
                                    it,
                                    title = "Playlist Name",
                                    initial = queueName.orEmpty(),
                                )
                            },
                            resultCallback = { name ->
                                if (!name.isNullOrBlank()) {
                                    App.scope.launch { App.playlists.createPlaylist(name, ids) }
                                    goBack()
                                }
                            },
                        )
                    }
                }
                // Last: a different kind of keeping, and its destructive half
                // shouldn't sit where a tap aimed at Like might land.
                DownloadRow(source, listOf(track), downloaded, "Add to Downloads")
            }
        }
    }
}
