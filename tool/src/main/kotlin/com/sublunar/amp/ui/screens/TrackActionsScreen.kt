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

class TrackActionsScreen(
    sealed: SealedLightActivity,
    private val trackId: String,
    private val queueIndex: Int? = null,
    /**
     * Set when opened from the player: the sheet then describes whatever is
     * playing *now*, not the track that was playing when it was opened. A song
     * ending while the menu is up would otherwise leave every row — like, rating,
     * artwork — acting on the previous one.
     */
    private val followCurrent: Boolean = false,
    // When set (opened from Now Playing), the lyrics row toggles the in-place
    // overlay on that screen instead of pushing a standalone lyrics screen.
    private val onToggleLyrics: (() -> Unit)? = null,
    private val lyricsShowing: Boolean = false,
    /**
     * Cleared by the player and the queue, where liking has its own control on
     * screen and a menu row for it would just be a second way to say the same.
     */
    private val showLike: Boolean = true,
    /**
     * Cleared by the player and the queue: what is playing is already downloaded
     * or already streaming, and managing storage is a library job.
     */
    private val showDownload: Boolean = true,
    /**
     * Cleared by the player, where rating and playlists live behind its "+"
     * button — the same action in two menus on one screen is one too many.
     */
    private val showRating: Boolean = true,
    /** Cleared by the player for the same reason as [showRating]. */
    private val showAddToPlaylist: Boolean = true,
    /** Set by the player; a cover at full width is only about what's playing. */
    private val onShowArtwork: (() -> Unit)? = null,
    /**
     * True when the cover is already filling the screen, which makes that row a
     * way back out of it.
     *
     * Only Controls on Cover can be in that state while this sheet is up — it
     * stays on the player rather than opening a page of its own, so the menu is
     * still reachable and has to say which way it goes. The other two layouts
     * are a separate screen with the menu behind them, and can only ever be
     * opened from here.
     */
    private val artworkShowing: Boolean = false,
    /** Set by lists that support multi-select; starts selection on this track. */
    private val onSelect: (() -> Unit)? = null,
    // When set (opened from a playlist detail), adds a "Remove from Playlist" row.
    private val onRemoveFromPlaylist: (() -> Unit)? = null,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val source by App.source.collectAsState()
        val tracks by App.library.tracks.collectAsState()
        val playing by App.playback.currentTrack.collectAsState()
        val id = if (followCurrent) playing?.id ?: trackId else trackId
        // The library row is preferred over the queue's copy: liked and rating
        // are edited here and the cached row is the one that gets updated.
        val track = tracks.firstOrNull { it.id == id } ?: playing?.takeIf { it.id == id }
        val downloadedIds by App.library.downloadedTrackIds.collectAsState()
        val downloaded = id in downloadedIds
        val queue by App.playback.queue.collectAsState()
        val queueIndexOf = queue.indexOfFirst { it.id == id }
        val inQueue = queueIndexOf >= 0
        // Already the next thing that will play: the row would do nothing.
        val playsNext = queueIndexOf == App.playback.index.collectAsState().value + 1
        // For the song already playing, liking is on the transport's heart and
        // queueing it again says nothing — so those rows are dropped.
        val isCurrent = playing?.id == id

        ListScreen(onBack = { goBack() }, title = track?.title ?: "Track", subtitle = track?.artist) {
            if (track == null) {
                EmptyState("Track not found")
                return@ListScreen
            }
            ActionList {
                // First: it is the way into multi-select, so it is the row that
                // changes what the rest of the app is doing rather than acting
                // on this one track.
                if (onSelect != null) {
                    TextRow(title = "Select") {
                        onSelect.invoke()
                        goBack()
                    }
                }
                if (queueIndex != null) {
                    TextRow(title = "Remove from Queue") {
                        App.playback.removeFromQueue(queueIndex)
                        goBack()
                    }
                }
                if (onRemoveFromPlaylist != null) {
                    TextRow(title = "Remove from Playlist") {
                        onRemoveFromPlaylist.invoke()
                        goBack()
                    }
                }
                if (!isCurrent) {
                    if (!playsNext) {
                        TextRow(title = "Play Next") {
                            App.playback.playNext(listOf(track))
                            goBack()
                        }
                    }
                    // Nothing to add when it's already there — the row would
                    // only ever produce a second copy of the same track.
                    if (!inQueue) {
                        TextRow(title = "Add to Queue") {
                            App.playback.addToQueue(listOf(track))
                            goBack()
                        }
                    }
                }
                if (showAddToPlaylist && source.supportsPlaylists) {
                    TextRow(title = "Add to Playlist") {
                        // Dismiss this sheet too once a playlist is picked, so the
                        // action doesn't leave its own menu sitting there.
                        navigateTo<Unit>(
                            { AddToPlaylistScreen(it, track.id) },
                            resultCallback = { goBack() },
                        )
                    }
                }
                if (showDownload && source.supportsDownloads) {
                    if (downloaded) {
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
                // Lyrics and the full cover belong to the track you're listening
                // to: both are things to look at *while* it plays, and only the
                // player can put them where they make sense. Elsewhere they were
                // a screen of words about a song that wasn't playing.
                if (onToggleLyrics != null) {
                    TextRow(title = if (lyricsShowing) "Hide Lyrics" else "Show Lyrics") {
                        onToggleLyrics.invoke()
                        goBack()
                    }
                }
                if (onShowArtwork != null) {
                    TextRow(
                        title = if (artworkShowing) "Hide Full Artwork" else "Show Full Artwork",
                    ) {
                        onShowArtwork.invoke()
                    }
                }
                // Beside the rating: both say what you think of the track, and
                // reading "Like" next to "Rating" is how you tell them apart.
                if (showLike && source.supportsLikes) {
                    TextRow(title = if (track.liked) "Unlike" else "Like") {
                        App.scope.launch { App.library.setTrackLiked(track, !track.liked) }
                        goBack()
                    }
                }
                if (showRating && source.supportsRatings) {
                    TextRow(title = "Rating", subtitle = ratingStars(track.rating)) {
                        go { RatingScreen(it, track.id, track.title, track.rating, isAlbum = false) }
                    }
                }
                // This sheet is opened from anywhere — a list, the queue, the
                // player — so neither destination has a parent on the stack:
                // both name the walk that leads to them.
                val artist = track.albumArtist.ifBlank { track.artist }
                if (track.albumId != null) {
                    TextRow(title = "Go to Album") {
                        openAlbum(track.albumId, Parent.artist(artist))
                    }
                }
                TextRow(title = "Go to Artist") {
                    openArtist(artist, Parent.tab(LibraryTab.ARTISTS))
                }
            }
        }
    }
}
