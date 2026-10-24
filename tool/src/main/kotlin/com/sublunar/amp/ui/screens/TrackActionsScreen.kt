package com.sublunar.amp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sublunar.amp.App
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.primaryAlbumArtist
import com.sublunar.amp.ui.components.ActionList
import com.sublunar.amp.ui.components.EmptyState
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Long-press actions for a track, from a list, the queue or the player. */
class TrackActionsScreen(
    sealed: SealedLightActivity,
    private val trackId: String,
    /** Set from the queue: adds "Remove from Queue". */
    private val queueIndex: Int? = null,
    /**
     * Set from the player: the sheet follows whatever is playing now, so a song
     * ending while it is up doesn't leave every row acting on the previous one.
     */
    private val followCurrent: Boolean = false,
    /** Set from the player: the lyrics row toggles the player's own overlay. */
    private val onToggleLyrics: (() -> Unit)? = null,
    private val lyricsShowing: Boolean = false,
    /** Cleared by the player, where liking has its own control on screen. */
    private val showLike: Boolean = true,
    /** Cleared by the player and the queue: managing storage is a library job. */
    private val showDownload: Boolean = true,
    /** Cleared by the player, where rating and playlists live behind its "+" button. */
    private val showRating: Boolean = true,
    /** Cleared by the player, as [showRating]. */
    private val showAddToPlaylist: Boolean = true,
    /** Set by the player: a cover at full width is only about what's playing. */
    private val onShowArtwork: (() -> Unit)? = null,
    /**
     * The cover already fills the screen (Controls on Cover, which keeps this sheet
     * reachable), so the artwork row is the way back out of it.
     */
    private val artworkShowing: Boolean = false,
    /** Set by lists that support multi-select: starts selection on this track. */
    private val onSelect: (() -> Unit)? = null,
    /** Set from a playlist's page: adds "Remove from Playlist". */
    private val onRemoveFromPlaylist: (() -> Unit)? = null,
    /** The song as the opening page holds it: a playlist can list one the chosen library doesn't. */
    private val known: Track? = null,
) : AmpScreen<Unit>(sealed) {

    // What the radio row is up to, said above the menu. Held on the screen, not in
    // the composition: the request outlives a recomposition and its answer must land.
    private var status by mutableStateOf<String?>(null)
    private var tuning by mutableStateOf(false)

    /** Set once this page has been popped, so an answer arriving late can't act on another. */
    private var gone = false

    override fun onScreenDestroy() {
        gone = true
    }

    @Composable
    override fun Content() {
        val source by App.source.collectAsState()
        val tracks by App.library.tracks.collectAsState()
        val cached by App.library.cachedTracks.collectAsState()
        val playing by App.playback.currentTrack.collectAsState()
        val id = if (followCurrent) playing?.id ?: trackId else trackId
        // The library row before the queue's copy: liked and rating are edited
        // here, and the library row is the one that gets updated.
        val track = remember(tracks, cached, playing, id) {
            tracks.firstOrNull { it.id == id }
                ?: cached.firstOrNull { it.id == id }
                ?: playing?.takeIf { it.id == id }
                ?: known?.takeIf { it.id == id }
        }
        val downloadedIds by App.library.downloadedTrackIds.collectAsState()
        val downloaded = id in downloadedIds
        val queue by App.playback.queue.collectAsState()
        val queueIndexOf = queue.indexOfFirst { it.id == id }
        val inQueue = queueIndexOf >= 0
        // Already the next thing that will play: the row would do nothing.
        val playsNext = queueIndexOf == App.playback.index.collectAsState().value + 1
        // Queueing the song already playing says nothing, so those rows go.
        val isCurrent = playing?.id == id

        ListScreen(onBack = { goBack() }, title = track?.title ?: "Track", subtitle = track?.artist) {
            if (track == null) {
                EmptyState("Track not found")
                return@ListScreen
            }
            ActionList {
                status?.let { SectionLabel(it) }
                // First: it changes what the list is doing rather than acting on
                // this one track.
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
                    // Already queued: the row would only add a second copy.
                    if (!inQueue) {
                        TextRow(title = "Add to Queue") {
                            App.playback.addToQueue(listOf(track))
                            goBack()
                        }
                    }
                }
                // A queue that starts on this song and carries on with what the
                // server thinks follows (LibraryRepository.radioFrom). The sheet
                // stays up while it asks, so "nothing" is said here rather than
                // something else played in its place.
                if (source.supportsRadio) {
                    TextRow(title = "Start Song Radio") {
                        if (tuning) return@TextRow
                        tuning = true
                        status = "Finding songs…"
                        App.scope.launch {
                            val radio = App.library.radioFrom(track)
                            // The player's looper is Main (see PlaybackController).
                            withContext(Dispatchers.Main) {
                                // Left while it was asking: the radio is dropped.
                                // Going back now would act on whatever page shows,
                                // and on the library's root that closes Amp.
                                if (gone) return@withContext
                                tuning = false
                                if (radio.isEmpty()) {
                                    status = "No radio for this song"
                                } else if (isCurrent) {
                                    // Seeded by the song already playing: it
                                    // carries on and the radio replaces what was
                                    // queued after it. The radio's first entry is
                                    // the seed itself, so it is skipped.
                                    status = null
                                    App.playback.replaceUpcoming(radio.drop(1), name = "${track.title} Radio")
                                    goBack()
                                } else {
                                    status = null
                                    App.playback.playQueue(radio, 0, name = "${track.title} Radio")
                                    replaceWithPlayer()
                                }
                            }
                        }
                    }
                }
                if (showAddToPlaylist) {
                    TextRow(title = "Add to Playlist") {
                        // Dismiss this sheet too once a playlist is picked.
                        navigateTo<Unit>(
                            { AddToPlaylistScreen(it, track.id) },
                            resultCallback = { goBack() },
                        )
                    }
                }
                if (showDownload && source.supportsDownloads && (downloaded || source.mayDownload)) {
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
                // Lyrics and the full cover are only offered by the player: they are
                // things to look at while the track plays.
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
                // Beside the rating: both say what you think of the track.
                if (showLike && source.supportsLikes) {
                    TextRow(title = if (track.liked) "Unlike" else "Like") {
                        App.scope.launch { App.library.setTrackLiked(track, !track.liked) }
                        goBack()
                    }
                }
                if (showRating && source.supportsRatings) {
                    TextRow(title = "Rating", value = ratingStars(track.rating)) {
                        go { RatingScreen(it, track.id, track.title, track.rating, isAlbum = false) }
                    }
                }
                // Opened from anywhere, so neither destination has a parent on the
                // stack: both name the walk that leads to them. One artist, the
                // record's first credit (see primaryAlbumArtist).
                val artist = track.primaryAlbumArtist()
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
