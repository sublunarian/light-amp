package com.sublunar.amp.ui.screens

import com.sublunar.amp.App
import com.sublunar.amp.data.LastSection
import com.sublunar.amp.data.Track
import com.sublunar.amp.ui.components.ScrollAnchors
import com.sublunar.amp.ui.components.SelectionState
import com.sublunar.amp.ui.components.Selections
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Library navigation state shared by the shell and every library sub-page.
 *
 * The tab bar is visible on sub-pages too, so a screen several levels deep has to
 * be able to select a tab. It records the choice here and unwinds to the root
 * screen, which owns the shell and observes this.
 */
object LibraryNav {
    val currentTab = MutableStateFlow(LibraryTab.ALBUMS)
    val searchActive = MutableStateFlow(false)
    val searchQuery = MutableStateFlow("")

    /**
     * Show the search page: straight to typing [withKeyboard] (the header's
     * magnifier), or with its last results.
     */
    fun openSearch(withKeyboard: Boolean = false) {
        searchActive.value = true
        record(LastSection.SEARCH)
        typing.value = withKeyboard
    }

    /**
     * Whether the keyboard is up.
     *
     * Search is two states of one page: typing, with the keyboard at the bottom
     * and the results above it, and reading, with the nav bar back. Return moves
     * from the first to the second and the results never go away, which is why
     * this isn't the SDK's full-screen editor.
     */
    val typing = MutableStateFlow(false)

    /**
     * The library index, where the simplified bar's library button leads.
     *
     * A state of the shell rather than a screen, because what it opens onto is a
     * tab, and tabs are states too. The index takes the shell's place the way
     * search does, a chosen tab replaces it, and that tab's header carries a back
     * arrow to the index. A real screen pushed from it leaves this set, so
     * backing out of one lands here too.
     */
    val libraryIndex = MutableStateFlow(false)

    /** Show the index, leaving whatever was showing. */
    fun openLibraryIndex() {
        closeSearch()
        libraryIndex.value = true
    }

    /**
     * The simplified bar's library button, pressed while the shell is showing.
     *
     * From search it comes back to the library as it was left. On the library it
     * goes up to the index, and pressing it there again does nothing: it is not
     * a toggle back down to the page.
     */
    fun pressLibrary() {
        if (searchActive.value) {
            closeSearch()
            return
        }
        libraryIndex.value = true
    }

    fun setQuery(query: String) {
        searchQuery.value = query
        searchActive.value = true
    }

    /** Leave the search page, keeping the query, so coming back finds the last results. */
    fun closeSearch() {
        searchActive.value = false
        typing.value = false
        record(LastSection.LIBRARY)
    }

    /**
     * Note where the app is, so it can reopen there.
     *
     * Silent until the app has booted: navigation can fire before the settings
     * store exists, and where the app reopens is not worth a crash on the way in.
     */
    internal fun record(section: LastSection) {
        if (!App.isReady) return
        App.scope.launch { App.settings.setLastSection(section) }
    }

    /**
     * The bar's tab, tapped. Tapping the one you are already on means back to the
     * top of it. A pushed page's bar pops to the root before it selects.
     */
    fun tapTab(tab: LibraryTab) {
        if (currentTab.value == tab && !searchActive.value && !libraryIndex.value) {
            ScrollAnchors.requestTop("tab:${tab.name.lowercase()}")
            return
        }
        selectTab(tab)
    }

    fun selectTab(tab: LibraryTab) {
        closeSearch()
        // Choosing from the index is leaving it — see [libraryIndex].
        libraryIndex.value = false
        // A selection belongs to the list it was made in: changing tabs abandons
        // it rather than leaving a stale count waiting on some other page.
        Selections.clearAll()
        // The tab keeps its scroll position. Asking for the top is [tapTab]'s job.
        currentTab.value = tab
        if (App.isReady) App.scope.launch { App.settings.setLastTab(tab.name) }
    }
}

/**
 * Which library page More was opened over, so it can show that list's view, sort
 * and filter. The page's header corner names it.
 *
 * A page identity rather than the settings themselves: More reads those live,
 * so a sort changed and stepped back from shows what it now is.
 */
enum class LibraryPage {
    /** The four tabs. */
    ALBUMS,
    SONGS,
    ARTISTS,
    PLAYLISTS,

    /** Results, which are the library in its own order. */
    SEARCH,

    /** The simplified layout's index of everything there is to browse. */
    LIBRARY,

    /** Pages whose order is the record's, the playlist's, or the server's. */
    ALBUM,
    ARTIST,
    ARTIST_SONGS,
    ARTIST_POPULAR,
    PLAYLIST,
}

/**
 * Push a screen that returns no result, as `go { SomeScreen(it) }`. [navigateTo]
 * takes an optional result callback last, which would capture the trailing lambda.
 */
fun SimpleLightScreen<*>.go(factory: (SealedLightActivity) -> SimpleLightScreen<Unit>) {
    navigateTo(factory)
}

/**
 * Push the bulk-action sheet for [tracks] and leave selection mode only if an
 * action actually ran — backing out of the sheet keeps a selection that may have
 * taken a while to build.
 */
fun SimpleLightScreen<*>.openSelectionActions(
    tracks: List<Track>,
    selection: SelectionState,
    showAddToQueue: Boolean = true,
    showDownload: Boolean = true,
) {
    if (tracks.isEmpty()) return
    navigateTo<Boolean>(
        { SelectionActionsScreen(it, tracks, showAddToQueue, showDownload) },
        resultCallback = { acted -> if (acted) selection.clear() },
    )
}

/**
 * Open the player from a long-press sheet, taking the sheet off the stack: back
 * from the player should land on the list the sheet was opened over.
 */
fun SimpleLightScreen<Unit>.replaceWithPlayer() {
    goBack()
    go { NowPlayingScreen(it) }
}

/** Long-press sheet for a track in a list that supports multi-select. */
fun SimpleLightScreen<*>.openTrackActions(trackId: String, selection: SelectionState?) {
    go { TrackActionsScreen(it, trackId, onSelect = selection?.let { s -> { s.begin(trackId) } }) }
}

/**
 * What the page being opened should have underneath it: where back leads.
 *
 * Back is "up one level in this tab", not "undo my last move". Walking down a
 * tab, the page you are on is the parent, so a push is all it takes. A jump from
 * outside the library (the player's menu, the queue, a search result) lays the
 * walk down first: unwind to the library, choose the tab, push the ancestors.
 * Back then walks that hierarchy and the last step lands on a tab root.
 *
 * The caller names which applies. Laying ancestors down is right from a tab list
 * and wrong from a genre or a playlist, which are real parents.
 */
sealed interface Parent {
    /** The page doing the opening — so simply push onto it. */
    data object Here : Parent

    /**
     * Nothing on the stack is the parent, so lay the ancestors down: unwind to
     * the library, select [tab], and — for a page that lives under an artist,
     * as an album lives under its artist's discography — push [artist] first.
     */
    data class Walk(val tab: LibraryTab, val artist: String? = null) : Parent

    companion object {
        /** A tab's own list, which is the top of its hierarchy. */
        fun tab(tab: LibraryTab): Parent = Walk(tab)

        /** An artist's discography, under Artists — where an album belongs. */
        fun artist(name: String): Parent =
            // A track carrying no artist at all has no discography to sit
            // under, so the album list is the nearest true parent left.
            if (name.isBlank()) Walk(LibraryTab.ALBUMS) else Walk(LibraryTab.ARTISTS, name)
    }
}

/**
 * Back to the library shell, however deep this page is: one back per screen
 * above it, all in this turn, so only the shell gets drawn. It stops at the
 * shell, because a back from there closes Amp.
 */
fun SimpleLightScreen<*>.backToRoot() {
    var popped = 0
    while (!BootScreen.isShowing && popped < MAX_UNWIND) {
        goBack()
        popped++
    }
}

private const val MAX_UNWIND = 64

/** Put [parent] on the stack, so that whatever is pushed next sits on top of it. */
private fun SimpleLightScreen<*>.layDown(parent: Parent) {
    if (parent !is Parent.Walk) return
    backToRoot()
    LibraryNav.selectTab(parent.tab)
    parent.artist?.let { artist -> go { ArtistDetailScreen(it, artist) } }
}

fun SimpleLightScreen<*>.openAlbum(albumId: String, parent: Parent) {
    layDown(parent)
    go { AlbumDetailScreen(it, albumId) }
}

fun SimpleLightScreen<*>.openArtist(name: String, parent: Parent) {
    if (name.isBlank()) return
    layDown(parent)
    go { ArtistDetailScreen(it, name) }
}
