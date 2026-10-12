package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.data.JellyfinClient
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.SyncState
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.PlayAllRow
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.sublunar.amp.data.LocalLibrary
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Reading the phone's own music; see LocalLibrary and lighttool.toml. */
private val READ_MEDIA_AUDIO = LocalLibrary.PERMISSION

/**
 * Which library the app is showing — and nothing else.
 *
 * This is the page reached while listening, so it does two things: every
 * source's libraries, one tap each, and Sync Now for the one in use. Adding a source, or changing anything about one,
 * lives in Settings under Sources — see [SourceListScreen]. Splitting them keeps
 * the switch you make often away from the settings you set once, where a
 * mistaken tap costs a re-sync.
 *
 * Each source keeps its own cache, downloads and likes, so switching is a swap
 * rather than a re-sync — and adding a second Navidrome (or the phone's own
 * files) doesn't disturb the first.
 */
class SourcesScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val active by App.settings.activeSource.collectAsState(initial = null)
        val activeLibrary by App.settings.libraryId.collectAsState(initial = null)
        val sync by App.library.syncState.collectAsState()

        ListScreen(onBack = { goBack() }, title = "Sources") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                // First, and always in the same place: a control shouldn't move
                // when the source changes. It refreshes the source in use — the
                // only one a sync can work on, and the only one that needs it,
                // since switching to another syncs that one as it opens. The
                // line under it names the source, so which one is never a guess.
                active?.let { current ->
                    item(key = "sync") {
                        TextRow(
                            title = if (sync.syncing) "Syncing…" else "Sync Now",
                            subtitle = "${current.name} · ${syncStatus(current, sync)}",
                            onClick = { if (!sync.syncing) App.library.scanAndSyncInBackground() },
                        )
                    }
                }
                sources.forEach { source ->
                    val chosen = source.id == active?.id
                    // The source is the heading its libraries sit under, not a
                    // row of its own: there is nothing here to do to a source,
                    // only libraries to choose between.
                    item(key = source.id) { SectionLabel(source.name) }
                    items(
                        rowsUnder(source, activeLibrary.takeIf { chosen }),
                        key = { "${source.id}/${it.libraryId ?: "all"}" },
                    ) { entry ->
                        SubRow(
                            name = entry.name,
                            selected = chosen && activeLibrary == entry.libraryId,
                            onClick = { switchTo(source, entry.libraryId) },
                        )
                    }
                }
            }
        }
    }

    /** How the source in use stands, for the line under Sync Now. */
    private fun syncStatus(source: MusicSource, sync: SyncState): String = when {
        sync.syncing -> sync.phase.ifBlank { "Working" }
        sync.error != null -> sync.error
        // Worth saying every time it is true: it means new files on the server
        // will never appear until the server itself is set to look for them.
        App.library.scanRefused -> "won't scan on request — last synced ${relativeTime(sync.lastSyncedMs)}"
        sync.lastSyncedMs > 0L -> "last synced ${relativeTime(sync.lastSyncedMs)}"
        source.kind == SourceKind.LOCAL -> "rescan this phone's music"
        // A server with no scan call of its own is only ever read from — see
        // MusicSource.supportsServerScan.
        !source.supportsServerScan -> "fetch the library again"
        else -> "scan the server, then refresh"
    }

    /** A row belonging to the source above it: one of its folders, or its page. */
    @Composable
    private fun SubRow(name: String, selected: Boolean = false, onClick: () -> Unit) {
        TextRow(
            title = name,
            modifier = Modifier.padding(start = px(FOLDER_INDENT_PX)),
            onClick = onClick,
            trailing = { if (selected) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
        )
    }

    /**
     * What sits under a source: one row per thing that can be listened to.
     *
     * [activeLibraryId] is the library in use when this is the source in use,
     * and it is shown whether or not it has been hidden — since the gear took
     * the source row's trailing slot, these rows carry the only mark of where
     * you are, and hiding the one you are on would leave the page unable to say.
     * Switch away and it disappears as asked.
     */
    private fun rowsUnder(source: MusicSource, activeLibraryId: String?): List<LibraryEntry> = when {
        source.kind == SourceKind.LOCAL ->
            listOf(LibraryEntry(LocalLibrary.FOLDER, null))
        // Several libraries shown: the whole server is a choice of its own, and
        // it has to be a row now that the source above doesn't select anything.
        // Which libraries appear here is the source's own setting — see
        // LibraryVisibilityScreen; All Libraries comes with two or more of
        // them, never by choice — see MusicSource.showsAllLibraries.
        source.showsAllLibraries ->
            listOf(LibraryEntry(ALL_LIBRARIES, null)) +
                source.libraries
                    .filter { it.id !in source.hiddenLibraryIds || it.id == activeLibraryId }
                    .map { LibraryEntry(it.name, it.id) }
        // All but one hidden: that one is everything shown, so its row means
        // the whole of what is shown, as a single library's always has.
        source.libraries.size >= 2 ->
            listOf(LibraryEntry(source.visibleLibraries.firstOrNull()?.name ?: ALL_LIBRARIES, null))
        // One library, or a server not yet synced: no choice to offer, just the
        // one row that turns it on.
        else -> listOf(LibraryEntry(source.libraries.firstOrNull()?.name ?: ALL_LIBRARIES, null))
    }

    /**
     * Switch the library over.
     *
     * Playback deliberately keeps going — the queue holds whole tracks rather
     * than lookups into the cache, so it stays valid across the swap and the user
     * decides when to change what is playing.
     */
    private fun switchTo(source: MusicSource, libraryId: String?) {
        // The results on screen were the old source's, and the index behind them
        // is dropped on the switch — so there is nothing left to come back to.
        LibraryNav.closeSearch()
        val changingLibrary = source.libraryId != libraryId
        App.scope.launch {
            // The folder is part of what "this source" means, so it is saved
            // before the switch — the library then syncs against the right one
            // rather than the previous choice.
            if (changingLibrary) {
                App.settings.setSourceLibraryId(source.id, libraryId)
            }
            App.settings.setActiveSource(source.id)

            // Only now. Unwinding puts the root screen back on top, and the root
            // screen syncs as it appears — reading which library to sync from
            // the store. Unwound before the write above had landed, that sync
            // read the *previous* library and fetched it again over the one just
            // chosen, which looks exactly like the switch never happened.
            withContext(Dispatchers.Main) { popToRoot() }

            if (source.id == App.source.value.id && changingLibrary) {
                App.library.switchLibrary(libraryId)
            }
        }
    }
}

/**
 * Which of a source's libraries Amp shows at all — see
 * MusicSource.hiddenLibraryIds. Hidden ones are off the Sources page, out of
 * All Libraries and every list, and not synced.
 *
 * A server can hold libraries this phone has no interest in — an Audiobooks
 * section beside the music — and every one of them was taking a row on the page
 * you use to switch. Ticked means shown; the last remaining one can't be
 * unticked, because those rows are the only way to select the source at all.
 */
class LibraryVisibilityScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
    /**
     * Opened while the source is being added, before its first sync: titled
     * as a choice, with a Done row, and handing back a result on Done and on
     * back alike — either way the adding carries on.
     */
    private val adding: Boolean = false,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val source = sources.firstOrNull { it.id == sourceId }

        ListScreen(onBack = { leave() }, title = if (adding) "Choose Libraries" else "Shown in Amp") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                if (source == null) return@ScrollableList
                item {
                    SectionLabel(
                        if (adding) "Tap to show or hide. Hidden libraries aren't synced." else "Tap to show or hide",
                    )
                }
                // Real libraries only: All Libraries comes and goes by itself —
                // see MusicSource.showsAllLibraries.
                items(source.libraries, key = { it.id }) { library ->
                    val shown = library.id !in source.hiddenLibraryIds
                    VisibilityRow(
                        name = library.name,
                        shown = shown,
                        onToggle = { toggle(library.id, !shown) },
                    )
                }
                if (adding) item { TextRow(title = "Done", onClick = { leave() }) }
            }
        }
    }

    /** A result only while adding: the SDK calls back only when there is one. */
    private fun leave() = if (adding) goBack(Unit) else goBack()

    @Composable
    private fun VisibilityRow(name: String, shown: Boolean, onToggle: () -> Unit) {
        TextRow(
            title = name,
            onClick = onToggle,
            trailing = { if (shown) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
        )
    }

    private fun toggle(libraryId: String, visible: Boolean) {
        App.scope.launch {
            App.settings.setLibraryVisible(sourceId, libraryId, visible)
            // A library shown again may never have been fetched — hidden while
            // the server was being added — so it is fetched now, not whenever
            // the next sync comes round.
            if (visible && App.source.value.id == sourceId) {
                withContext(Dispatchers.Main) { App.library.syncInBackground(force = true) }
            }
        }
    }
}

/**
 * The last step of adding a source, the same for every kind: with two or more
 * libraries, offer them to pick from first (see [LibraryVisibilityScreen]);
 * then make the source active, which starts its first sync, and land on its
 * albums. Active only after the choice, so a library hidden there is never
 * fetched. The source must already be saved, under [sourceId].
 */
fun SimpleLightScreen<*>.finishAddingSource(sourceId: String, libraryCount: Int) {
    if (libraryCount >= 2) {
        navigateTo<Unit>(
            { LibraryVisibilityScreen(it, sourceId, adding = true) },
            resultCallback = { activateAddedSource(sourceId) },
        )
    } else {
        activateAddedSource(sourceId)
    }
}

/**
 * Save a server a sign-in found, then [finishAddingSource] — unless the page
 * that found it has [gone] meanwhile, since navigating from it then would
 * act on whatever page is on top.
 *
 * The id carried on is whatever saveSource settled on, not the one the
 * sign-in minted: re-linking a server already known by its own id updates
 * that source in place rather than adding a second — see
 * AppSettings.saveSource.
 */
fun SimpleLightScreen<*>.adoptServer(server: PendingServer, gone: () -> Boolean) {
    App.scope.launch {
        val id = App.settings.saveSource(server.source)
        withContext(Dispatchers.Main.immediate) {
            if (gone()) return@withContext
            finishAddingSource(id, server.sections.size)
        }
    }
}

private fun SimpleLightScreen<*>.activateAddedSource(sourceId: String) {
    App.scope.launch {
        App.settings.setActiveSource(sourceId)
        withContext(Dispatchers.Main) {
            LibraryNav.selectTab(LibraryTab.ALBUMS)
            popToRoot()
        }
    }
}

/** The row that means the whole server rather than one of its libraries. */
private const val ALL_LIBRARIES = "All Libraries"

/** One row under a source: a library to switch to, or the whole server. */
private data class LibraryEntry(val name: String, val libraryId: String?)

/** How far a folder sits inside its server. */
private const val FOLDER_INDENT_PX = 60


/**
 * Managing sources: adding them, and reaching each one's settings.
 *
 * The counterpart to [SourcesScreen], which only switches between libraries.
 * This lives in Settings because everything on it is something you do once —
 * naming a server, setting its quality, logging out of it.
 */
class SourceListScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())

        ListScreen(onBack = { goBack() }, title = "Sources") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                items(sources, key = { it.id }) { source ->
                    TextRow(
                        title = source.name,
                        subtitle = subtitleFor(source),
                        onClick = { go { SourceDetailScreen(it, source.id) } },
                    )
                }
                item { SectionLabel("Add") }
                item {
                    PlayAllRow(AppIcons.Add, "Add Source") { go { AddSourceScreen(it) } }
                }
            }
        }
    }
}

/**
 * The three kinds of source, offered as equals.
 *
 * One screen rather than a set of rows repeated wherever sources can be added:
 * the first-run screen used to carry its own copy of this and quietly lacked
 * Plex, because the second copy was never updated when Plex arrived.
 */
class AddSourceScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())

        ListScreen(onBack = { goBack() }, title = "Add Source") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                item { SectionLabel("Subsonic covers Navidrome, Airsonic and Ampache") }
                item {
                    PlayAllRow(AppIcons.Add, "Bandcamp") {
                        go { ServerScreen(it, sourceId = null, adding = true, bandcamp = true) }
                    }
                }
                item {
                    PlayAllRow(AppIcons.Add, "Subsonic Server") {
                        go { ServerScreen(it, sourceId = null, adding = true) }
                    }
                }
                item {
                    PlayAllRow(AppIcons.Add, "Plex Server") { go { PlexLinkScreen(it) } }
                }
                item {
                    PlayAllRow(AppIcons.Add, "Jellyfin Server") { go { JellyfinLinkScreen(it) } }
                }
                // Only ever one: it is the phone, and there is only one phone.
                if (sources.none { it.kind == SourceKind.LOCAL }) {
                    item {
                        PlayAllRow(AppIcons.Smartphone, "Music on This Phone") { addLocal() }
                    }
                }
            }
        }
    }

    private fun addLocal() {
        App.scope.launch {
            val local = MusicSource.local()
            App.settings.saveSource(local)
            App.settings.setActiveSource(local.id)
        }
        goBack()
    }
}

/** Where a source's music comes from, for the row that names it. */
private fun subtitleFor(source: MusicSource): String = when (source.kind) {
    SourceKind.LOCAL -> LocalLibrary.FOLDER
    SourceKind.SUBSONIC, SourceKind.PLEX, SourceKind.JELLYFIN ->
        source.baseUrl.ifBlank { "Not connected" }
}

/**
 * One source's own settings: what it is called, and how it is reached.
 *
 * Nothing else. This page used to carry the source's streaming and download
 * quality and its offline behaviour too, which buried the connection rows under
 * knobs that get revisited; those live under Data and Offline now — see
 * [DataScreen] and [OfflineScreen] — leaving this a page you visit to point the
 * app at a server and then leave alone.
 */
class SourceDetailScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val source = sources.firstOrNull { it.id == sourceId }
        val sync by App.library.syncState.collectAsState()

        // Re-checked on every sync, because granting access is what the user
        // goes off to do and coming back is when it should have changed.
        var access by remember { mutableStateOf(LocalLibrary.Access.GRANTED) }
        LaunchedEffect(sync) { access = LocalLibrary.access() }
        val canRead = access == LocalLibrary.Access.GRANTED
        val audioPermission = rememberPermissionRequestLauncher(READ_MEDIA_AUDIO)

        ListScreen(onBack = { goBack() }, title = source?.name ?: "Source") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                if (source == null) return@ScrollableList
                if (source.kind == SourceKind.LOCAL && !canRead) {
                    // The folder listing comes back empty rather than failing
                    // without the permission, so without this the source would
                    // just look like a phone with no music on it.
                    item {
                        TextRow(
                            title = "Allow Music Access",
                            subtitle = if (access == LocalLibrary.Access.BLOCKED_BY_LIGHTOS) {
                                "LightOS is blocking it for this tool"
                            } else {
                                "Needed to read this phone's music folder"
                            },
                            onClick = { audioPermission?.launch() },
                        )
                    }
                }
                if (source.kind == SourceKind.SUBSONIC) {
                    item {
                        TextRow(
                            title = "Connection",
                            value = source.baseUrl.ifBlank { "Not set" },
                            onClick = { go { ServerScreen(it, sourceId = source.id) } },
                        )
                    }
                } else if (source.kind == SourceKind.JELLYFIN) {
                    // Signing in again, here rather than by logging out and
                    // adding the server anew, keeps its library and downloads.
                    item {
                        TextRow(
                            title = "Connection",
                            value = source.baseUrl.ifBlank { "Not set" },
                            subtitle = "Sign in again",
                            onClick = { go { JellyfinLinkScreen(it, again = source) } },
                        )
                    }
                } else if (source.kind != SourceKind.LOCAL) {
                    // Plex signs in by token, so there are no fields to edit —
                    // a different server or account means linking again, which
                    // updates this source in place. The row still answers where
                    // the music comes from; a stating row takes no press — see
                    // TextRow.
                    item {
                        TextRow(
                            title = "Connection",
                            value = source.baseUrl.ifBlank { "Not set" },
                        )
                    }
                }
                // What this source reports itself as to the server — the
                // scrobbling client Navidrome names a "Player" from, or Plex's
                // product/device fields. Blank keeps the app's own default.
                if (source.kind != SourceKind.LOCAL) {
                    item {
                        TextRow(
                            title = "Player name",
                            value = source.playerName.ifBlank { "Amp (default)" },
                            onClick = {
                                navigateTo<String?>(
                                    {
                                        TextEntryScreen(
                                            it,
                                            title = "Player name",
                                            initial = source.playerName,
                                        )
                                    },
                                    resultCallback = { text ->
                                        if (text != null) {
                                            App.scope.launch {
                                                App.settings.saveSource(
                                                    source.copy(playerName = text.trim()),
                                                )
                                            }
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
                // Only worth offering when there is a choice to make: with one
                // library there is one row under the source and nothing to hide.
                if (source.libraries.size >= 2) {
                    item { SectionLabel("Library") }
                    item {
                        TextRow(
                            title = "Shown in Amp",
                            value = "${source.visibleLibraries.size} of ${source.libraries.size}",
                            onClick = { go { LibraryVisibilityScreen(it, source.id) } },
                        )
                    }
                }
                // Logging out of a server *is* forgetting it now that there can
                // be several: one row, named for what it does to this kind of
                // source.
                item { SectionLabel("Remove") }
                item {
                    TextRow(
                        title = if (source.kind == SourceKind.LOCAL) {
                            "Remove Source"
                        } else {
                            "Log Out"
                        },
                        subtitle = "Forgets its library and its downloads",
                        onClick = {
                            // Asked first: what goes with it can't be had back
                            // without the network, and downloads are what this
                            // phone keeps for when there is none.
                            val local = source.kind == SourceKind.LOCAL
                            navigateTo<Boolean>(
                                {
                                    ConfirmScreen(
                                        it,
                                        title = if (local) "Remove Source" else "Log Out",
                                        message = if (local) {
                                            "This removes ${source.name} from Amp. The music " +
                                                "files stay on this phone."
                                        } else {
                                            "This removes ${source.name} from Amp and deletes " +
                                                "all of its downloads from this phone."
                                        },
                                        confirmLabel = if (local) "Remove" else "Log Out",
                                    )
                                },
                                resultCallback = { confirmed ->
                                    if (confirmed == true) {
                                        // Stop first: the queue holds stream URLs
                                        // signed for the server we are about to forget.
                                        if (source.id == App.source.value.id) App.playback.stop()
                                        // Ended on the server too, on its own: the
                                        // phone stops being listed there, and nothing
                                        // here waits on a server that may be gone.
                                        if (source.kind == SourceKind.JELLYFIN) {
                                            App.scope.launch { signOutQuietly(source) }
                                        }
                                        App.scope.launch {
                                            // Wipe while the source still exists to
                                            // name its database and its download folder.
                                            App.forgetSource(source)
                                            App.settings.removeSource(source.id)
                                        }
                                        goBack()
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

/** Log Out's word to a Jellyfin server — see [JellyfinClient.signOut]. Best effort, and brief. */
private suspend fun signOutQuietly(source: MusicSource) {
    val client = source.toClient() as? JellyfinClient ?: return
    try {
        runCatching { withTimeoutOrNull(SIGN_OUT_TIMEOUT_MS) { client.signOut() } }
    } finally {
        client.close()
    }
}

private const val SIGN_OUT_TIMEOUT_MS = 5_000L
