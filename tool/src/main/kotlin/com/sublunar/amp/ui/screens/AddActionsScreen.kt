package com.sublunar.amp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.sublunar.amp.App
import com.sublunar.amp.ui.components.ActionList
import com.sublunar.amp.ui.components.EmptyState
import com.sublunar.amp.ui.components.ListScreen
import com.thelightphone.sdk.SealedLightActivity

/**
 * The player's "+" sheet: the ways of keeping the track that's playing (liked, in a
 * playlist, rated, on the device). The ••• menu is about the track itself; keeping
 * the queue as a playlist is on the queue's own — see [QueueActionsScreen].
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

        ListScreen(
            onBack = { goBack() },
            title = track?.title ?: "Track",
            subtitle = track?.artist,
            headingRows = true,
        ) {
            if (track == null) {
                EmptyState("Track not found")
                return@ListScreen
            }
            ActionList {
                if (source.supportsLikes) LikeTrackRow(track)
                if (source.supportsRatings) RatingRow(track.id, track.title, track.rating, isAlbum = false)
                AddToPlaylistRow(listOf(track.id)) { goBack() }
                // Last: a different kind of keeping, and its destructive half
                // shouldn't sit where a tap aimed at Like might land.
                DownloadRow(source, listOf(track), downloaded, "Add to Downloads")
            }
        }
    }
}
