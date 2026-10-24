package com.sublunar.amp.ui.screens

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
import androidx.compose.ui.graphics.vector.ImageVector
import com.sublunar.amp.App
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.SyncState
import com.sublunar.amp.source.AddAction
import com.sublunar.amp.source.AddEntry
import com.sublunar.amp.source.AddIcon
import com.sublunar.amp.source.PendingServer
import com.sublunar.amp.source.PhoneAccess
import com.sublunar.amp.source.Providers
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.PlayAllRow
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * Which library the app is showing, and nothing else: every source's libraries,
 * one tap each, and Sync Now (Rescan Library for the phone's own music) for the
 * one in use. Adding or changing a source is in Settings — see
 * [SourceListScreen] — away from the switch you make often.
 *
 * Each source keeps its own cache, downloads and likes, so switching is a swap
 * rather than a re-sync.
 */
class SourcesScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val sources by App.settings.sources.state.collectAsState()
        val active by App.settings.activeSource.state.collectAsState()
        val activeLibrary by App.settings.libraryId.state.collectAsState()
        val sync by App.librarySync.syncState.collectAsState()

        ListScreen(onBack = { goBack() }, title = "Sources") {
            ScrollableList {
                // First, so it doesn't move when the source changes. It refreshes
                // the source in use, the only one a sync can work on (switching
                // syncs the new one as it opens), and the line under names it.
                active?.let { current ->
                    item(key = "sync") {
                        val phone = current.phoneLibrary != null
                        TextRow(
                            title = when {
                                sync.syncing -> if (phone) "Scanning…" else "Syncing…"
                                phone -> "Rescan Library"
                                else -> "Sync Now"
                            },
                            subtitle = "${current.name} · ${syncStatus(current, sync)}",
                            onClick = { if (!sync.syncing) App.librarySync.scanAndSyncInBackground() },
                        )
                    }
                }
                sources.forEach { source ->
                    val chosen = source.id == active?.id
                    // The source is the heading its libraries sit under: there is
                    // nothing here to do to a source, only libraries to choose.
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
        // Worth saying every time: new files on the server won't appear until
        // the server itself is set to look for them.
        App.librarySync.scanRefused -> "won't scan on request — last synced ${relativeTime(sync.lastSyncedMs)}"
        sync.lastSyncedMs > 0L && source.phoneLibrary != null -> "last scanned ${relativeTime(sync.lastSyncedMs)}"
        sync.lastSyncedMs > 0L -> "last synced ${relativeTime(sync.lastSyncedMs)}"
        source.phoneLibrary != null -> "rescan this phone's music"
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
            checked = selected,
        )
    }

    /**
     * What sits under a source: one row per thing that can be listened to.
     *
     * [activeLibraryId], the library in use, is shown even when hidden: these
     * rows carry the only mark of where you are. Switch away and it goes.
     */
    private fun rowsUnder(source: MusicSource, activeLibraryId: String?): List<LibraryEntry> {
        // The phone's own music is one folder, by definition.
        source.phoneLibrary?.let { return listOf(LibraryEntry(it.folderLabel, null)) }
        return when {
            // Several libraries shown: the whole server is a row of its own. Which
            // libraries appear is the source's setting (LibraryVisibilityScreen),
            // and All Libraries comes with two or more — see
            // MusicSource.showsAllLibraries.
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
    }

    /** Switch the library over — see App.useSource. */
    private fun switchTo(source: MusicSource, libraryId: String?) {
        // The results on screen were the old source's, and the switch drops them.
        LibraryNav.closeSearch()
        App.useSource(source, libraryId) { withContext(Dispatchers.Main) { backToRoot() } }
    }
}

/**
 * Which of a source's libraries Amp shows at all (an Audiobooks section beside
 * the music, say) — see MusicSource.hiddenLibraryIds. Hidden ones are off the
 * Sources page, out of All Libraries and every list, and not synced.
 */
class LibraryVisibilityScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
    /**
     * Opened while the source is being added, before its first sync: titled as a
     * choice, with a Done row, and handing back a result on Done and on back
     * alike, since the adding carries on either way.
     */
    private val adding: Boolean = false,
) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val sources by App.settings.sources.state.collectAsState()
        val source = sources.firstOrNull { it.id == sourceId }

        ListScreen(onBack = { leave() }, title = if (adding) "Choose Libraries" else "Shown in Amp") {
            ScrollableList {
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
                    TextRow(title = library.name, checked = shown) { toggle(library.id, !shown) }
                }
                if (adding) item { TextRow(title = "Done", onClick = { leave() }) }
            }
        }
    }

    /** A result only while adding: the SDK calls back only when there is one. */
    private fun leave() = if (adding) goBack(Unit) else goBack()

    private fun toggle(libraryId: String, visible: Boolean) {
        App.scope.launch {
            App.settings.setLibraryVisible(sourceId, libraryId, visible)
            // A library shown again may never have been fetched (hidden while
            // the server was being added), so it is fetched now.
            if (visible && App.source.value.id == sourceId) {
                withContext(Dispatchers.Main) { App.librarySync.syncInBackground(force = true) }
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
 * that found it has [gone] meanwhile, since navigating from it then would act
 * on whatever page is on top.
 *
 * Carries on with the id saveSource settled on, not the one the sign-in minted:
 * re-linking a known server updates it in place — see AppSettings.saveSource.
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
            backToRoot()
        }
    }
}

/** "3 min ago" and friends, for the last-synced line. */
private fun relativeTime(ms: Long): String {
    val delta = System.currentTimeMillis() - ms
    if (delta < 0) return "just now"
    val minutes = delta / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}

/** The row that means the whole server rather than one of its libraries. */
private const val ALL_LIBRARIES = "All Libraries"

/** One row under a source: a library to switch to, or the whole server. */
private data class LibraryEntry(val name: String, val libraryId: String?)

/** How far a folder sits inside its server. */
private const val FOLDER_INDENT_PX = 60

/**
 * Managing sources: adding them, and reaching each one's settings. The
 * counterpart to [SourcesScreen], which only switches between libraries.
 */
class SourceListScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val sources by App.settings.sources.state.collectAsState()

        ListScreen(onBack = { goBack() }, title = "Sources") {
            ScrollableList {
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

/** Every kind of source, offered as equals — from [Providers.addEntries], as first run is. */
class AddSourceScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val sources by App.settings.sources.state.collectAsState()

        ListScreen(onBack = { goBack() }, title = "Add Source") {
            ScrollableList {
                Providers.addNotes.forEach { note -> item { SectionLabel(note) } }
                Providers.addEntries(sources).forEach { entry ->
                    item {
                        PlayAllRow(entry.icon.vector, entry.label) { begin(entry, thenBack = true) }
                    }
                }
            }
        }
    }
}

/**
 * Start adding a source the way [entry] says, from Add Source or the
 * first-run screen. Only Add Source goes back once a source is added at
 * once ([thenBack]): the first-run screen is the root, and turns into the
 * library by itself as soon as there is a source.
 */
internal fun SimpleLightScreen<*>.begin(entry: AddEntry, thenBack: Boolean) {
    when (val action = entry.action) {
        is AddAction.OpenScreen -> go(action.screen)
        is AddAction.AddAtOnce -> {
            App.scope.launch {
                val source = action.source()
                App.settings.saveSource(source)
                App.settings.setActiveSource(source.id)
            }
            if (thenBack) goBack()
        }
    }
}

private val AddIcon.vector: ImageVector
    get() = when (this) {
        AddIcon.ADD -> AppIcons.Add
        AddIcon.PHONE -> AppIcons.Smartphone
    }

/** Where a source's music comes from, for the row that names it. */
private fun subtitleFor(source: MusicSource): String =
    source.phoneLibrary?.folderLabel ?: source.baseUrl.ifBlank { "Not connected" }

/**
 * One source's own settings: what it is called, and how it is reached. Its
 * qualities and downloads are under [DataScreen] and [OfflineScreen].
 */
class SourceDetailScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val sources by App.settings.sources.state.collectAsState()
        val source = sources.firstOrNull { it.id == sourceId }
        val sync by App.librarySync.syncState.collectAsState()

        // Re-checked on every sync, since granting access is what the user goes
        // off to do. Keyed on the source too: the list starts empty, and an
        // answer taken before it loaded would stand until the next sync.
        var access by remember { mutableStateOf(PhoneAccess.GRANTED) }
        LaunchedEffect(sync, source?.id) { access = source?.phoneLibrary?.access() ?: PhoneAccess.GRANTED }
        val canRead = access == PhoneAccess.GRANTED
        val askAccess = rememberAccessRequest(source?.phoneLibrary)

        ListScreen(onBack = { goBack() }, title = source?.name ?: "Source") {
            ScrollableList {
                if (source == null) return@ScrollableList
                if (source.phoneLibrary != null && !canRead) {
                    // Without the permission the folder listing comes back empty
                    // rather than failing, which looks like a phone with no music.
                    item {
                        TextRow(
                            title = "Allow Music Access",
                            subtitle = if (access == PhoneAccess.BLOCKED_BY_LIGHTOS) {
                                "LightOS is blocking it for this tool"
                            } else {
                                "Needed to read this phone's music folder"
                            },
                            onClick = askAccess,
                        )
                    }
                }
                // How this kind of source is reached is its own to say — see
                // SourceUi.connection.
                source.provider.ui.connection(source)?.let { row ->
                    item {
                        TextRow(
                            title = "Connection",
                            value = source.baseUrl.ifBlank { "Not set" },
                            subtitle = row.subtitle,
                            onClick = row.open?.let { open -> { go(open) } },
                        )
                    }
                }
                // What this source reports itself as to the server — the
                // scrobbling client Navidrome names a "Player" from, or Plex's
                // product/device fields. Blank keeps the app's own default.
                if (source.hasServer) {
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
                // Only with a choice to make: one library has nothing to hide.
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
                // Logging out of a server is forgetting it: one row, named for
                // what it does to this kind of source.
                item { SectionLabel("Remove") }
                item {
                    val local = !source.hasServer
                    TextRow(
                        title = if (local) "Remove Source" else "Log Out",
                        subtitle = "Forgets its library and its downloads",
                        onClick = {
                            // Asked first: what goes with it can't be had back
                            // without the network.
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
                                        App.removeSource(source)
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
