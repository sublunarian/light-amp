package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.art.ArtworkNeed
import com.sublunar.amp.data.Album
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.shuffled
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.components.AppArtwork
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.CenteredNote
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.LibraryList
import com.sublunar.amp.ui.components.NumberedRow
import com.sublunar.amp.ui.components.ROW_GAP_PX
import com.sublunar.amp.ui.components.ROW_LEAD_PX
import com.sublunar.amp.ui.components.ROW_SUB_LINE_PX
import com.sublunar.amp.ui.components.ROW_SUB_PX
import com.sublunar.amp.ui.components.SelectionHeader
import com.sublunar.amp.ui.components.appClickable
import com.sublunar.amp.ui.components.formatRunTime
import com.sublunar.amp.ui.components.formatTime
import com.sublunar.amp.ui.components.rememberSelection
import com.sublunar.amp.ui.components.rowClickable
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.launch

class AlbumDetailScreen(
    sealed: SealedLightActivity,
    private val albumId: String,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker (see handleVolumeKey).
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    /** On the screen, which outlives its composition (the SDK disposes it under a pushed page). */
    private var refreshed = false

    @Composable
    override fun Content() {
        val albums by App.library.albums.collectAsState()
        val album = remember(albums, albumId) { albums.firstOrNull { it.id == albumId } }
        val current by App.playback.currentTrack.collectAsState()

        var tracks by remember(albumId) { mutableStateOf<List<Track>?>(null) }
        LaunchedEffect(albumId) {
            tracks = App.library.getAlbumTracks(albumId)
            // Once per visit, not on every return from a page opened over it.
            if (!refreshed) {
                refreshed = true
                App.library.refreshAlbumTracks(albumId)?.let { tracks = it }
            }
        }

        val selection = rememberSelection("album:$albumId")

        LibrarySubPage {
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
                    // The album's name alone: the artist is in the card below.
                    title = album?.title ?: "Album",
                    // The album's own menu (a record has no order to choose), the
                    // same as a long-press on its row.
                    rightAction = HeaderAction(
                        AppIcons.MoreVert,
                        onLongClick = { go { SettingsScreen(it) } },
                    ) {
                        go { AlbumActionsScreen(it, albumId) }
                    },
                    fitTitle = true,
                )
            }

            val list = tracks
            if (list == null) {
                CenteredNote("Loading…")
            } else {
                // Credits show only where the tracks' own artists vary, and then on
                // every track. Judged against each other, not the album's artist: a
                // classical record's tracks can all carry the composer while the
                // album credits the performer.
                val varied = remember(list) {
                    list.map { it.artist.trim().lowercase() }.distinct().size > 1
                }
                LibraryList(
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
                            subtitle = if (varied) track.artist else "",
                            detail = formatTime(track.durationMs),
                            current = current?.id == track.id,
                            selected = if (selection.active) track.id in selection.selected else null,
                            coverArtId = track.coverArtId,
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

    /**
     * The record at the head of its track list: cover, what it is, and like and
     * shuffle as bare icons (tapping any track already plays the album).
     */
    @Composable
    private fun AlbumCard(album: Album, tracks: List<Track>) {
        val source by App.source.collectAsState()
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = px(CARD_GAP_PX)),
        ) {
            // The sleeve starts where every list's covers start, after the
            // leading slot.
            Spacer(Modifier.width(px(ROW_LEAD_PX)))
            AppArtwork(
                coverArtId = album.coverArtId,
                size = px(CARD_ART_PX),
                fallback = AppIcons.Album,
                // The album that is open, not one in a list (see ArtworkNeed).
                need = ArtworkNeed.FOCUSED,
                // Held, not tapped, like every artwork in the app: a tap at the
                // top of a list scrolled past constantly would open it by accident.
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
}

/** The record's own block at the head of its list. */
private const val CARD_ART_PX = 300
private const val CARD_TITLE_PX = LightType.COPY_PX
private const val CARD_TITLE_LINE_PX = LightType.COPY_LINE_PX
private const val CARD_GAP_PX = 24
private const val CARD_ICON_PX = 60
private const val CARD_ICON_GAP_PX = 66
