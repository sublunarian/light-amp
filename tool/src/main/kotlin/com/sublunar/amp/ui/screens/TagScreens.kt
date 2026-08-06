package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.shuffled
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.EmptyState
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.SplitActionRow
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.components.TrackRow
import com.sublunar.amp.ui.components.rememberListAnchor
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen

/**
 * The library through one of its tags: genre, composer, or the compilation flag.
 *
 * These are the fields a Subsonic server carries but doesn't give a browse
 * endpoint for, so each list is derived from the cached tracks. They only appear
 * on the More page at all when the active server actually fills them in — see
 * [MoreScreen] — because on a library with no composer tags a Composers page is
 * an empty room.
 */
class GenresScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val genres by App.library.genres.collectAsState()

        LibrarySubPage {
            AppHeader(
                onBack = { goBack() },
                title = "Genres",
                searchAction = { openLibrarySearch(withKeyboard = true) },
                rightAction = HeaderAction(AppIcons.Waveform) { go { NowPlayingScreen(it) } },
            )
            ScrollableList(
                state = rememberListAnchor("genres"),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (genres.isEmpty()) item { EmptyState("No genres in this library") }
                items(genres, key = { it }) { genre ->
                    TextRow(title = genre) { go { TagSongsScreen(it, genre, byComposer = false) } }
                }
            }
        }
    }
}

class ComposersScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val composers by App.library.composers.collectAsState()

        LibrarySubPage {
            AppHeader(
                onBack = { goBack() },
                title = "Composers",
                searchAction = { openLibrarySearch(withKeyboard = true) },
                rightAction = HeaderAction(AppIcons.Waveform) { go { NowPlayingScreen(it) } },
            )
            ScrollableList(
                state = rememberListAnchor("composers"),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (composers.isEmpty()) item { EmptyState("No composers in this library") }
                items(composers, key = { it }) { composer ->
                    TextRow(title = composer) {
                        go { TagSongsScreen(it, composer, byComposer = true) }
                    }
                }
            }
        }
    }
}

/** Every song carrying one tag value, in the songs list's own style. */
class TagSongsScreen(
    sealed: SealedLightActivity,
    private val tag: String,
    private val byComposer: Boolean,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val library by App.library.tracks.collectAsState()
        val songs: List<Track> = remember(library, tag, byComposer) {
            if (byComposer) App.library.tracksWithComposer(tag) else App.library.tracksWithGenre(tag)
        }
        val downloadedIds by App.library.downloadedTrackIds.collectAsState()

        LibrarySubPage {
            AppHeader(
                onBack = { goBack() },
                title = tag,
                searchAction = { openLibrarySearch(withKeyboard = true) },
                rightAction = HeaderAction(AppIcons.Waveform) { go { NowPlayingScreen(it) } },
            )
            ScrollableList(
                state = rememberListAnchor("tag:$tag", headerCount = 1),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    SplitActionRow(
                        leftIcon = AppIcons.PlayArrow,
                        leftLabel = "Play",
                        onLeft = {
                            App.playback.playQueue(songs, 0)
                            go { NowPlayingScreen(it) }
                        },
                        rightIcon = AppIcons.Shuffle,
                        rightLabel = "Shuffle",
                        onRight = {
                            App.playback.playQueue(shuffled(songs), 0)
                            go { NowPlayingScreen(it) }
                        },
                    )
                }
                if (songs.isEmpty()) item { EmptyState("Nothing here") }
                itemsIndexed(songs, key = { _, t -> t.id }) { index, track ->
                    TrackRow(
                        title = track.title,
                        subtitle = track.artist,
                        coverArtId = track.coverArtId,
                        downloaded = track.id in downloadedIds,
                        onClick = {
                            App.playback.playQueue(songs, index)
                            go { NowPlayingScreen(it) }
                        },
                        onLongClick = { openTrackActions(track.id, null) },
                    )
                }
            }
        }
    }
}

/** Albums the server marks as compilations, in the albums list's own style. */
class CompilationsScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val albums by App.library.compilations.collectAsState()
        val downloadedAlbums by App.library.downloadedAlbumIds.collectAsState()

        LibrarySubPage {
            AppHeader(
                onBack = { goBack() },
                title = "Compilations",
                searchAction = { openLibrarySearch(withKeyboard = true) },
                rightAction = HeaderAction(AppIcons.Waveform) { go { NowPlayingScreen(it) } },
            )
            ScrollableList(
                state = rememberListAnchor("compilations"),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (albums.isEmpty()) item { EmptyState("No compilations in this library") }
                items(albums, key = { it.id }) { album ->
                    TrackRow(
                        title = album.title,
                        subtitle = album.artist,
                        coverArtId = album.coverArtId,
                        fallback = AppIcons.Album,
                        downloaded = album.id in downloadedAlbums,
                        onClick = { go { AlbumDetailScreen(it, album.id) } },
                        onLongClick = { go { AlbumActionsScreen(it, album.id) } },
                    )
                }
            }
        }
    }
}
