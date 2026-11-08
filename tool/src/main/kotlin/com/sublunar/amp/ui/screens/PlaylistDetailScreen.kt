package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.sublunar.amp.App
import com.sublunar.amp.data.Album
import com.sublunar.amp.data.PlaylistSortChoice
import com.sublunar.amp.data.PlaylistTrackSort
import com.sublunar.amp.data.PlaylistView
import com.sublunar.amp.data.nameKey
import com.sublunar.amp.data.titleKey
import androidx.compose.foundation.lazy.items
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.shuffled
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.AutoScroll
import com.sublunar.amp.ui.components.CenteredNote
import com.sublunar.amp.ui.components.DRAG_HANDLE_SLOT_PX
import com.sublunar.amp.ui.components.DragHandle
import com.sublunar.amp.ui.components.DropIndicatorLine
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.LIST_EDGE_PX
import com.sublunar.amp.ui.components.NOTICE_MS
import com.sublunar.amp.ui.components.NoticePill
import com.sublunar.amp.ui.components.ROW_H_PX
import com.sublunar.amp.ui.components.ShuffleRow
import com.sublunar.amp.ui.components.SCROLLBAR_LANE_PX
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.SelectionHeader
import com.sublunar.amp.ui.components.SelectionState
import com.sublunar.amp.ui.components.Selections
import com.sublunar.amp.ui.components.TrackRow
import com.sublunar.amp.ui.components.dragReorderContainer
import com.sublunar.amp.ui.components.dropTarget
import com.sublunar.amp.ui.components.gapShift
import com.sublunar.amp.ui.components.moveShownBlock
import com.sublunar.amp.ui.components.rememberDragReorderState
import com.sublunar.amp.ui.components.rememberListAnchor
import com.sublunar.amp.ui.components.rememberSelection
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.SealedLightActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlaylistDetailScreen(
    sealed: SealedLightActivity,
    private val playlistId: String,
    private val playlistName: String,
) : AmpScreen<Unit>(sealed) {

    // Held on the screen instance (the SDK disposes the composition under a pushed
    // sheet), so local edits survive the track-options sheet without a reload.
    private val entries = mutableStateOf<List<PlaylistEntry>?>(null)

    // Up briefly after a write fails: the list has just gone back to how it was,
    // which otherwise looks like the edit never happened.
    private val saveFailed = mutableStateOf(false)

    // Edits are written one at a time in the order made (see [edit]). lastEdit is
    // the tail of that line. editEpoch moves on when a write fails, which tells the
    // edits queued behind it that the list they were made against is gone.
    private var lastEdit: Job? = null
    @Volatile private var editEpoch = 0

    // Whether [entries] is the server's whole playlist rather than a rebuild (see
    // PlaylistView). Removing and reordering address the server's copy by position,
    // so neither is offered on a rebuild. Rows another library holds are in [entries]
    // too, just not shown, so a playlist narrowed to one library is still whole.
    // Set with each answer, the same snapshot as the list: false for the stored copy.
    private val wholeList = mutableStateOf(true)

    // Whether the last answer has come. Until it has, coming back to the page asks again:
    // leaving it cancelled the wait for the server.
    private var settled = false

    // Whether the library is down to its downloads, kept live: a playlist left open
    // when Wi-Fi drops would otherwise keep offering edits that can only fail.
    private val offline = mutableStateOf(false)

    /**
     * Whether this playlist may be edited: online (an edit touches the network, which
     * offline modes forbid), and whole (a partial list can't be addressed by position).
     */
    private fun canEdit(): Boolean = wholeList.value && !offline.value

    // This playlist's own sort — see AppSettings.playlistSorts.
    private val sortKey = playlistSortKey(playlistId)

    /** Into edit mode, back in Playlist Order: the order is what an edit changes. */
    private fun startEditing(selection: SelectionState, key: String? = null) {
        App.scope.launch { App.settings.setPlaylistSort(sortKey, PlaylistSortChoice()) }
        selection.begin(key)
    }

    // A playlist can hold a song twice, so rows use a synthetic key, not track.id.
    private var nextEntryKey = 0
    private fun newEntryKey(): String = "e${nextEntryKey++}"

    // The same songs in the same order keep their rows, so a server that agrees only makes the list editable.
    private fun show(view: PlaylistView) {
        wholeList.value = view.complete
        val current = entries.value
        if (current != null &&
            current.map { it.track.id } == view.all.map { it.id } &&
            current.map { it.shown } == view.shown
        ) return
        // New rows, new keys: what was ticked no longer names anything.
        if (current != null) Selections.of("playlist:$playlistId").let { if (it.active) it.begin() }
        entries.value = entriesOf(view)
    }

    @Composable
    override fun Content() {
        LaunchedEffect(playlistId) {
            if (!settled) {
                // Ticks left from an earlier visit name rows of a list read afresh here.
                if (entries.value == null) Selections.of("playlist:$playlistId").clear()
                App.playlists.playlistViews(playlistId).collect { show(it) }
                settled = true
            }
        }
        // Collected for the life of the screen: the half of canEdit that can change
        // while the playlist sits open.
        LaunchedEffect(Unit) { App.offlineOnly.collect { offline.value = it } }
        val (sort, reversed) = playlistSortOf(sortKey)
        val albums by App.library.albums.collectAsState()

        val selection = rememberSelection("playlist:$playlistId")

        // The stored name, not the one this screen was opened with: the corner menu
        // can rename the playlist while this page is under it.
        val liveName = App.playlists.allPlaylists.collectAsState().value
            .firstOrNull { it.id == playlistId }?.name ?: playlistName

        LibrarySubPage {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    if (selection.active) {
                        SelectionHeader(
                            selection = selection,
                            // No delete at all where it could only fail.
                            onDelete = if (!canEdit()) null else {
                                {
                                    removeSongs(selection.selected)
                                    // Stay in edit mode, empty: pruning a playlist is
                                    // usually more than one pass.
                                    selection.begin()
                                }
                            },
                            onConfirm = {
                                openSelectionActions(
                                    selection.pick(shownEntries()) { it.key }.map { it.track },
                                    selection,
                                )
                            },
                        )
                    } else {
                        AppHeader(
                            onBack = { goBack() },
                            title = liveName,
                            // More, as every library page has, with Edit at its top.
                            rightAction = HeaderAction(
                                AppIcons.Sort,
                                onLongClick = { go { SettingsScreen(it) } },
                            ) {
                                go {
                                    MoreScreen(
                                        it,
                                        LibraryPage.PLAYLIST,
                                        liveName,
                                        playlistSortKey = sortKey,
                                        onEdit = { startEditing(selection) },
                                    )
                                }
                            },
                            fitTitle = true,
                        )
                    }
                    when (val list = entries.value) {
                        null -> CenteredNote("Loading…")
                        else -> {
                            val shown = remember(list, sort, reversed, albums) {
                                sortedEntries(list.filter { it.shown }, sort, reversed, albums)
                            }
                            // A drag means a new position only in the playlist's own order, forwards.
                            val reorderable = sort == PlaylistTrackSort.PLAYLIST_ORDER && !reversed
                            when {
                                shown.isNotEmpty() -> TrackList(shown, selection, reorderable)
                                // Neither the server's copy nor one held: not known to be empty.
                                list.isEmpty() && !wholeList.value && !offline.value -> CenteredNote("Couldn't load")
                                list.isEmpty() || offline.value -> CenteredNote("Empty playlist")
                                else -> CenteredNote("No songs in this library")
                            }
                        }
                    }
                }
                if (saveFailed.value) NoticePill("Couldn't save")
            }
        }
    }

    /**
     * One playlist row: [key] is a synthetic per-row id, [track] what it holds. [shown]
     * is false for a song the chosen library doesn't hold, which stays in place for the
     * edits to work against but isn't listed.
     */
    internal data class PlaylistEntry(val key: String, val track: Track, val shown: Boolean = true)

    private fun entriesOf(view: PlaylistView): List<PlaylistEntry> =
        view.all.mapIndexed { i, track -> PlaylistEntry(newEntryKey(), track, view.shown[i]) }

    private fun shownEntries(): List<PlaylistEntry> =
        sortedEntries(
            entries.value.orEmpty().filter { it.shown },
            (App.settings.playlistSorts.value[sortKey] ?: PlaylistSortChoice()).sort,
            (App.settings.playlistSorts.value[sortKey] ?: PlaylistSortChoice()).reversed,
            App.library.albums.value,
        )

    /** The tracks. Edit mode is one mode: the grab bars appear and rows become selectable together. */
    @Composable
    private fun TrackList(
        list: List<PlaylistEntry>,
        selection: SelectionState,
        /** In the playlist's own order, where a drag means a new position; sorted otherwise. */
        reorderable: Boolean,
    ) {
        val editing = selection.active
        val drag = rememberDragReorderState<String>()
        val rowPx = with(LocalDensity.current) { px(ROW_H_PX).toPx() }
        val downloaded by App.library.downloadedTrackIds.collectAsState()
        val headerCount = if (editing) 0 else 1
        val listState = rememberListAnchor("playlist:$playlistId", headerCount)
        val orderedKeys = remember(list) { list.map { it.key } }
        val dropTarget = drag.dropTarget(orderedKeys, rowPx)

        drag.AutoScroll(listState, rowPx)

        Box(
            Modifier
                .fillMaxSize()
                .dragReorderContainer(
                    state = drag,
                    enabled = editing && canEdit() && reorderable,
                    restartKey = list,
                    orderedKeys = orderedKeys,
                    rowPx = rowPx,
                    groupOf = { hitKey ->
                        if (hitKey in selection.selected && selection.count > 1) selection.selected else setOf(hitKey)
                    },
                    onDrop = { movingIndices, insertAt -> reorderGroup(movingIndices, insertAt) },
                ),
        ) {
            ScrollableList(state = listState) {
                if (!editing) {
                    item {
                        ShuffleRow {
                            App.playback.playQueue(shuffled(list.map { it.track }), 0)
                            go { NowPlayingScreen(it) }
                        }
                    }
                }
                itemsIndexed(list, key = { _, e -> e.key }) { index, entry ->
                    val track = entry.track
                    val isDragging = entry.key in drag.draggingKeys
                    // Clear coords on dispose: LazyColumn recycles nodes, so a stale
                    // entry would report the next occupant's position.
                    DisposableEffect(entry.key) {
                        onDispose { drag.clear(entry.key) }
                    }
                    val gap = drag.gapShift(index, list.size, rowPx)
                    Box(Modifier.fillMaxWidth().graphicsLayer { translationY = gap }) {
                        TrackRow(
                            title = track.title,
                            subtitle = track.artist,
                            coverArtId = track.coverArtId,
                            modifier = Modifier
                                .onGloballyPositioned { drag.rowCoords[entry.key] = it }
                                // Real row goes invisible; DragOverlay draws the floating stand-in.
                                .alpha(if (isDragging) 0f else 1f),
                            onClick = {
                                if (editing) {
                                    selection.toggle(entry.key)
                                } else {
                                    playFromRow(list.map { it.track }, index)
                                }
                            },
                            onLongClick = {
                                if (!editing) {
                                    go {
                                        TrackActionsScreen(
                                            it, track.id,
                                            onSelect = { startEditing(selection, entry.key) },
                                            // Absent where it could only fail, as the delete icon.
                                            onRemoveFromPlaylist =
                                                if (canEdit()) ({ removeSongs(setOf(entry.key)) }) else null,
                                            known = track,
                                        )
                                    }
                                }
                            },
                            downloaded = track.id in downloaded,
                            selected = if (editing) entry.key in selection.selected else null,
                            trailing = if (editing && canEdit() && reorderable) {
                                { DragHandle { drag.iconCoords[entry.key] = it } }
                            } else {
                                null
                            },
                        )
                        if (dropTarget?.beforeKey == entry.key) DropIndicatorLine(Modifier.align(Alignment.TopStart))
                    }
                }
                if (dropTarget != null && dropTarget.beforeKey == null) {
                    item { DropIndicatorLine() }
                }
            }
            if (drag.draggingIndex != null) {
                DragOverlay(list, drag.draggingKeys, drag.dragStartTops, drag.fingerOffsetY, selection, downloaded)
            }
        }
    }

    /**
     * Floating copy of the row(s) being dragged, drawn outside the LazyColumn so it
     * survives the real row being recycled by auto-scroll. [fingerOffsetY] excludes
     * auto-scroll's own contribution, since the overlay's position shouldn't move twice.
     */
    @Composable
    private fun BoxScope.DragOverlay(
        list: List<PlaylistEntry>,
        draggingKeys: Set<String>,
        dragStartTops: Map<String, Float>,
        fingerOffsetY: Float,
        selection: SelectionState,
        downloaded: Set<String>,
    ) {
        list.forEach { entry ->
            if (entry.key !in draggingKeys) return@forEach
            val top = dragStartTops[entry.key] ?: return@forEach
            TrackRow(
                title = entry.track.title,
                subtitle = entry.track.artist,
                coverArtId = entry.track.coverArtId,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .graphicsLayer { translationY = top + fingerOffsetY }
                    .padding(start = px(LIST_EDGE_PX), end = px(SCROLLBAR_LANE_PX)),
                onClick = {},
                downloaded = entry.track.id in downloaded,
                selected = entry.key in selection.selected,
                trailing = { Spacer(Modifier.width(px(DRAG_HANDLE_SLOT_PX))) },
            )
        }
    }

    /**
     * Show an edit now and write it behind the screen's back.
     *
     * [after] goes on screen at once. [write] waits its turn behind every earlier
     * edit, which keeps positions honest: each edit is worked out against the list
     * the edits before it leave, which is the list the server holds once they land.
     *
     * A failed write breaks that chain, so the edits queued behind it are dropped
     * unsent ([editEpoch]). The screen goes back to the server's own copy where it
     * can be read (a Plex edit is many requests and can stop partway), otherwise to
     * [entries] as they were before the failed edit.
     */
    private fun edit(after: List<PlaylistEntry>, write: suspend () -> Boolean) {
        val before = entries.value ?: return
        entries.value = after
        val previous = lastEdit
        val epoch = editEpoch
        lastEdit = App.scope.launch {
            previous?.join()
            if (epoch != editEpoch) return@launch
            if (write()) {
                // The playlists page's track counts, not this write's own success.
                App.playlists.refreshPlaylists()
                return@launch
            }
            editEpoch++
            val view = App.playlists.playlistView(playlistId)
            if (view.complete && view.all.map { it.id } != before.map { it.track.id }) {
                // New rows, new keys: what was ticked no longer names anything.
                Selections.of("playlist:$playlistId").let { if (it.active) it.begin() }
                entries.value = entriesOf(view)
            } else {
                entries.value = before
            }
            saveFailed.value = true
            delay(NOTICE_MS)
            saveFailed.value = false
        }
    }

    /**
     * Remove rows by their keys, not their songs: a playlist can hold a song twice,
     * and ticking one means that one. The server is told positions in the whole
     * playlist, shown or not (see [MusicServer.removeFromPlaylistAt]).
     */
    private fun removeSongs(keys: Set<String>) {
        // Positions into [entries] name other songs unless it is the whole playlist.
        if (!canEdit()) return
        val current = entries.value ?: return
        // Only rows on screen: another library's songs are never the user's to remove here.
        val doomed = current.filter { it.shown && it.key in keys }.mapTo(HashSet()) { it.key }
        // Each position with the song seen there, so a playlist edited elsewhere in
        // the meantime is refused rather than mis-pruned.
        val at = current.indices.filter { current[it].key in doomed }.associateWith { current[it].track.id }
        if (at.isEmpty()) return
        edit(current.filterNot { it.key in doomed }) {
            App.playlists.removeFromPlaylistAt(playlistId, at)
        }
    }

    /**
     * Move the shown rows at [indices], in their order, as a block to [insertAt] among
     * the rest shown. Rows that aren't shown keep their places (see [moveShownBlock]).
     */
    private fun reorderGroup(indices: Set<Int>, insertAt: Int) {
        // A partial order would be an instruction to lose everything it omits.
        if (!canEdit()) return
        val current = entries.value ?: return
        val newList = moveShownBlock(current, { it.key }, { it.shown }, indices, insertAt)
        if (newList == current) return
        edit(newList) { App.playlists.reorderPlaylist(playlistId, newList.map { it.track.id }) }
    }
}

/**
 * [entries] in [sort]'s order: as they come for the playlist's own, otherwise by
 * the chosen key with the record's own order to break ties. [reversed] turns the
 * whole list round.
 */
private fun <E> sortedEntries(
    entries: List<E>,
    sort: PlaylistTrackSort,
    reversed: Boolean,
    albums: List<Album>,
    track: (E) -> Track,
): List<E> {
    val sorted = sortedEntries(entries, sort, albums, track)
    return if (reversed) sorted.asReversed() else sorted
}

private fun <E> sortedEntries(entries: List<E>, sort: PlaylistTrackSort, albums: List<Album>, track: (E) -> Track): List<E> {
    val onRecord = compareBy<E>({ titleKey(track(it).album) }, { track(it).discNumber ?: 0 }, { track(it).trackNumber ?: 0 })
    return when (sort) {
        PlaylistTrackSort.PLAYLIST_ORDER -> entries
        PlaylistTrackSort.TITLE -> entries.sortedBy { titleKey(track(it).title) }
        PlaylistTrackSort.ARTIST -> entries.sortedWith(compareBy<E> { nameKey(track(it).artist) }.then(onRecord))
        PlaylistTrackSort.ALBUM -> entries.sortedWith(onRecord)
        PlaylistTrackSort.RELEASE_DATE -> {
            // The full date where the album has one, so a year's releases still order; newest first.
            val released = albums.associate { it.id to if (it.releaseDate != 0L) it.releaseDate else (it.year ?: 0) * 10_000L }
            entries.sortedWith(
                compareByDescending<E> { released[track(it).albumId] ?: (track(it).year ?: 0) * 10_000L }.then(onRecord),
            )
        }
    }
}

private fun sortedEntries(
    entries: List<PlaylistDetailScreen.PlaylistEntry>,
    sort: PlaylistTrackSort,
    reversed: Boolean,
    albums: List<Album>,
) = sortedEntries(entries, sort, reversed, albums) { it.track }

/** Where a playlist's own sort is stored: by source as well, since two servers' ids can match. */
fun playlistSortKey(playlistId: String): String = "${App.source.value.id}:$playlistId"

/** The playlist's sort as stored, Playlist Order where it has none. */
@Composable
fun playlistSortOf(key: String): PlaylistSortChoice =
    App.settings.playlistSorts.state.collectAsState().value[key] ?: PlaylistSortChoice()

/**
 * "Sort by" for one playlist's songs, as the tabs' are: picking one applies it and
 * closes, tapping the current one again reverses it, and the arrow shows which way.
 */
class PlaylistTrackSortScreen(
    sealed: SealedLightActivity,
    private val sortKey: String,
) : AmpScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val (sort, reversed) = playlistSortOf(sortKey)
        ListScreen(onBack = { goBack() }, title = "Sort by", subtitle = "Songs", headingRows = true) {
            ScrollableList {
                item { SectionLabel("Tap the current sort again to reverse it") }
                items(PlaylistTrackSort.entries) { option ->
                    val selected = option == sort
                    TextRow(
                        title = option.label,
                        onClick = {
                            App.scope.launch {
                                val next = if (selected) {
                                    PlaylistSortChoice(sort, !reversed)
                                } else {
                                    PlaylistSortChoice(option)
                                }
                                App.settings.setPlaylistSort(sortKey, next)
                            }
                            goBack(Unit)
                        },
                        trailing = {
                            if (selected) {
                                val descending = option.descendingByNature != reversed
                                AppIcon(
                                    if (descending) AppIcons.ArrowDownward else AppIcons.ArrowUpward,
                                    size = px(SORT_ARROW_PX),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
