package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.sublunar.amp.App
import com.sublunar.amp.ui.components.EmptyState
import com.sublunar.amp.ui.components.ActionList
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.launch

/**
 * The player's "+" sheet: the ways of keeping the track that's playing.
 *
 * Separate from the ••• menu rather than folded into it — that one is about the
 * track (lyrics, artist, album, artwork), while these are the ways of keeping it:
 * liked, in a playlist, rated, or on the device — and, for a queue worth
 * keeping whole, the queue itself as a playlist.
 */
class AddActionsScreen(
    sealed: SealedLightActivity,
    private val trackId: String,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val source by App.source.collectAsState()
        val tracks by App.library.tracks.collectAsState()
        // Follows the player: opened from it, it should describe what is playing
        // rather than what was playing when the button was pressed.
        val playing by App.playback.currentTrack.collectAsState()
        val id = playing?.id ?: trackId
        val track = tracks.firstOrNull { it.id == id } ?: playing?.takeIf { it.id == id }
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
                if (source.supportsLikes) {
                    TextRow(title = if (track.liked) "Unlike" else "Like") {
                        App.scope.launch { App.library.setTrackLiked(track, !track.liked) }
                        goBack()
                    }
                }
                if (source.supportsRatings) {
                    TextRow(title = "Rating", subtitle = ratingStars(track.rating)) {
                        go { RatingScreen(it, track.id, track.title, track.rating, isAlbum = false) }
                    }
                }
                if (source.supportsPlaylists) {
                    TextRow(title = "Add to Playlist") {
                        navigateTo<Unit>(
                            { AddToPlaylistScreen(it, track.id) },
                            resultCallback = { goBack() },
                        )
                    }
                }
                // The whole queue, kept. This is how a song radio becomes
                // something you can come back to — or download — instead of
                // the app guessing which radios to cache: you hear it, you
                // decide. The name is offered where the queue has one ("Judith
                // Radio") and left to you where it is just some songs.
                if (source.supportsPlaylists && queue.isNotEmpty()) {
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
                                    App.scope.launch { App.library.createPlaylist(name, ids) }
                                    goBack()
                                }
                            },
                        )
                    }
                }
                // Last, and below the rest: keeping a copy is a different kind of
                // "add" from the three above it, and the destructive half of it
                // shouldn't sit where a tap aimed at Like might land.
                if (!source.supportsDownloads) {
                    // Nothing to keep a copy of: the file is already on the phone.
                } else if (downloaded) {
                    TextRow(title = "Remove from Downloads") {
                        App.scope.launch { App.downloader.remove(track.id) }
                        goBack()
                    }
                } else {
                    TextRow(title = "Add to Downloads") {
                        App.downloader.enqueue(listOf(track))
                        goBack()
                    }
                }
            }
        }
    }
}
