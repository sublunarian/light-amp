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
import com.sublunar.amp.data.TagSort
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.shuffled
import com.sublunar.amp.data.sortAlbums
import com.sublunar.amp.data.sortTags
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.EmptyState
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.LibraryList
import com.sublunar.amp.ui.components.listSearch
import com.sublunar.amp.ui.components.PlayAllRow
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
    override fun Content() = TagList(
        title = "Genres",
        byComposer = false,
        empty = "No genres in this library",
    )
}

class ComposersScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() = TagList(
        title = "Composers",
        byComposer = true,
        empty = "No composers in this library",
    )
}

/**
 * The shared body of the two tag lists: the same page over a different field.
 *
 * A library page in its own right, so there is no back button — the bar below is
 * how you leave, and the corner it would have taken holds the sort menu instead.
 */
@Composable
private fun SimpleLightScreen<*>.TagList(title: String, byComposer: Boolean, empty: String) {
    val values by (if (byComposer) App.library.composers else App.library.genres)
        .collectAsState()
    val tracks by App.library.tracks.collectAsState()
    val sort by App.tagSort.collectAsState()
    val reversed by App.tagSortReversed.collectAsState()
    // Counted once, for the order and for the rows: counting walks every track
    // in the library, and only one of the two orders needs it at all.
    val counts = remember(tracks, sort) {
        if (sort == TagSort.SONGS) App.library.tagCounts(byComposer) else emptyMap()
    }
    val ordered = remember(values, counts, sort, reversed) {
        sortTags(values, sort, reversed) { counts }
    }

    LibrarySubPage {
        AppHeader(
            leftAction = HeaderAction(AppIcons.Waveform) { go { NowPlayingScreen(it) } },
            title = title,
            onTitleClick = titleMenu { go { TagsSortScreen(it, title) } },
            rightAction = libraryCorner { go { TagsSortScreen(it, title) } },
        )
        LibraryList(
            anchor = if (byComposer) "composers" else "genres",
            onSearch = listSearch { openLibrarySearch(withKeyboard = true) },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (ordered.isEmpty()) item { EmptyState(empty) }
            items(ordered, key = { it }) { value ->
                TextRow(
                    title = value,
                    // Empty in name order, so the page stays the clean list of
                    // words it was: the number is there to explain an order that
                    // would otherwise look arbitrary, not to decorate the rows.
                    subtitle = counts[value]?.let { n -> "$n ${if (n == 1) "song" else "songs"}" },
                ) {
                    go { TagSongsScreen(it, value, byComposer) }
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
                rightAction = libraryCorner(),
                fitTitle = true,
            )
            LibraryList(
                anchor = "tag:$tag",
                headerCount = 1,
                onSearch = listSearch { openLibrarySearch(withKeyboard = true) },
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    PlayAllRow(AppIcons.Shuffle, "Shuffle") {
                        App.playback.playQueue(shuffled(songs), 0)
                        go { NowPlayingScreen(it) }
                    }
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
        val all by App.library.compilations.collectAsState()
        // The same order the album lists are in — a compilation is an album, and
        // keeping a sort of its own would be one more thing to set twice.
        val sort by App.albumSort.collectAsState()
        val reversed by App.albumSortReversed.collectAsState()
        val albums = remember(all, sort, reversed) { sortAlbums(all, sort, reversed) }
        val downloadedAlbums by App.library.downloadedAlbumIds.collectAsState()

        LibrarySubPage {
            AppHeader(
                leftAction = HeaderAction(AppIcons.Waveform) { go { NowPlayingScreen(it) } },
                title = "Compilations",
                onTitleClick = titleMenu { go { AlbumsSortScreen(it, "Compilations") } },
                rightAction = libraryCorner { go { AlbumsSortScreen(it, "Compilations") } },
            )
            LibraryList(
                anchor = "compilations",
                onSearch = listSearch { openLibrarySearch(withKeyboard = true) },
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
                        // The compilations list is a real parent, so back returns
                        // to it rather than to the Albums tab.
                        onClick = { openAlbum(album.id, Parent.Here) },
                        onLongClick = { go { AlbumActionsScreen(it, album.id) } },
                    )
                }
            }
        }
    }
}
