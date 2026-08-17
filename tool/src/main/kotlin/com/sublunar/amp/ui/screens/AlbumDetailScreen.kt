package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.shuffled
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.LibraryList
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.NumberedRow
import com.sublunar.amp.ui.components.SelectionHeader
import com.sublunar.amp.ui.components.rememberListAnchor
import com.sublunar.amp.ui.components.rememberSelection
import com.sublunar.amp.ui.nSp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import com.sublunar.amp.data.Album
import com.sublunar.amp.ui.components.AppArtwork
import com.sublunar.amp.ui.components.appClickable
import com.sublunar.amp.ui.components.rowClickable
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.ROW_GAP_PX
import com.sublunar.amp.ui.components.ROW_SUB_PX
import com.sublunar.amp.ui.components.ROW_SUB_LINE_PX
import com.sublunar.amp.ui.components.formatRunTime
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import kotlinx.coroutines.launch

class AlbumDetailScreen(
    sealed: SealedLightActivity,
    private val albumId: String,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val albums by App.library.albums.collectAsState()
        val album = remember(albums, albumId) { albums.firstOrNull { it.id == albumId } }
        val current by App.playback.currentTrack.collectAsState()

        var tracks by remember(albumId) { mutableStateOf<List<Track>?>(null) }
        LaunchedEffect(albumId) { tracks = App.library.getAlbumTracks(albumId) }

        val selection = rememberSelection("album:$albumId")

        LibrarySubPage {
            run {
                if (selection.active) {
                    SelectionHeader(selection) {
                        openSelectionActions(
                            selection.pick(tracks.orEmpty()) { it.id },
                            selection,
                        )
                    }
                } else {
                    AppHeader(
                        onBack = { goBack() },
                        // The album's name alone. The artist is on every row
                        // below and in the cover art, so a second line here was
                        // repeating what the page already says.
                        title = album?.title ?: "Album",
                        rightAction = libraryCornerAction(),
                        fitTitle = true,
                    )
                }

                val list = tracks
                when {
                    list == null -> Loading()
                    else -> LibraryList(
                        anchor = "album:$albumId",
                        headerCount = if (selection.active) 0 else 1,
                modifier = Modifier.fillMaxSize(),
            ) {
                        if (!selection.active && album != null) {
                            item { AlbumCard(album, list) }
                        }
                        itemsIndexed(list, key = { _, t -> t.id }) { index, track ->
                            NumberedRow(
                                number = track.trackNumber ?: (index + 1),
                                title = track.title,
                                durationMs = track.durationMs,
                                current = current?.id == track.id,
                                selected = if (selection.active) track.id in selection.selected else null,
                                onClick = {
                                    if (selection.active) {
                                        selection.toggle(track.id)
                                    } else {
                                        App.playback.playQueue(list, index)
                                        go { NowPlayingScreen(it) }
                                    }
                                },
                                onLongClick = {
                                    if (!selection.active) openTrackActions(track.id, selection)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * The record itself, at the head of its own track list: cover on the left,
     * what it is on the right, and the two things you might do to it.
     *
     * Icons without labels for the pair — beside the title and the year they
     * read as marks on a sleeve rather than as buttons competing with it, and
     * tapping any track plays the album anyway, so a "Play" button would be the
     * least useful thing on the page.
     */
    @Composable
    private fun AlbumCard(album: Album, tracks: List<Track>) {
        val source by App.source.collectAsState()
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = px(CARD_GAP_PX)),
        ) {
            AppArtwork(
                coverArtId = album.coverArtId,
                size = px(CARD_ART_PX),
                fallback = AppIcons.Album,
                // Held, not tapped. The sleeve sits at the top of a list you
                // scroll past constantly, and a tap target there is one flicked
                // thumb away from a full-screen picture nobody asked for. The
                // long press matches how every other artwork in the app opens
                // something.
                modifier = Modifier.rowClickable(
                    onClick = {},
                    onLongClick = { go { AlbumArtworkScreen(it, album.id) } },
                ),
            )
            Spacer(Modifier.width(px(ROW_GAP_PX)))
            Column(modifier = Modifier.weight(1f)) {
                AppText(
                    album.title,
                    pxSp(CARD_TITLE_PX),
                    lineHeight = pxSp(CARD_TITLE_LINE_PX),
                    maxLines = 2,
                )
                AppText(
                    album.artist,
                    pxSp(ROW_SUB_PX),
                    lineHeight = pxSp(ROW_SUB_LINE_PX),
                    dim = true,
                    maxLines = 1,
                )
                AppText(
                    albumFacts(album, tracks),
                    pxSp(ROW_SUB_PX),
                    lineHeight = pxSp(ROW_SUB_LINE_PX),
                    dim = true,
                    maxLines = 1,
                )
                Spacer(Modifier.height(px(CARD_GAP_PX)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (source.supportsLikes) {
                        AppIcon(
                            if (album.liked) AppIcons.Favorite else AppIcons.FavoriteBorder,
                            size = px(CARD_ICON_PX),
                            modifier = Modifier.appClickable {
                                App.scope.launch { App.library.setAlbumLiked(album, !album.liked) }
                            },
                        )
                        Spacer(Modifier.width(px(CARD_ICON_GAP_PX)))
                    }
                    AppIcon(
                        AppIcons.Shuffle,
                        size = px(CARD_ICON_PX),
                        modifier = Modifier.appClickable {
                            App.playback.playQueue(shuffled(tracks), 0)
                            go { NowPlayingScreen(it) }
                        },
                    )
                }
            }
        }
    }

    /** Year · N songs · total running time, skipping whatever isn't known. */
    private fun albumFacts(album: Album, tracks: List<Track>): String {
        val songs = if (tracks.isNotEmpty()) tracks.size else album.songCount
        val lengthMs = if (tracks.isNotEmpty()) tracks.sumOf { it.durationMs } else album.durationMs
        return listOfNotNull(
            album.year?.toString(),
            "$songs ${if (songs == 1) "song" else "songs"}",
            formatRunTime(lengthMs).takeIf { lengthMs > 0 },
        ).joinToString(" · ")
    }

    @Composable
    private fun Loading() {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AppText("Loading…", nSp(16), dim = true)
        }
    }
}

/** The record's own block at the head of its list. */
private const val CARD_ART_PX = 300
private const val CARD_TITLE_PX = 60
private const val CARD_TITLE_LINE_PX = 72
private const val CARD_GAP_PX = 24
private const val CARD_ICON_PX = 60
private const val CARD_ICON_GAP_PX = 66
