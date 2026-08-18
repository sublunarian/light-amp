package com.sublunar.amp.ui.screens

import com.sublunar.amp.data.Track
import com.sublunar.amp.ui.components.SelectionState
import com.sublunar.amp.ui.components.ScrollAnchors
import com.sublunar.amp.ui.components.Selections
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.flow.MutableStateFlow

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
     * True while a library page that is not one of the tabs is showing — a liked
     * list, a genre, the downloads.
     *
     * Those pages are peers of the tabs rather than pages *of* one, so the bar
     * lights nothing while they are up, exactly as it does for search. It can't
     * be answered from [currentTab]: that is still "the tab you would return
     * to", and it has to keep saying so for when one is next tapped. Sub-pages
     * opened from a standalone page inherit the state — an album reached from
     * Liked Albums is no more "in Albums" than the liked list was.
     *
     * Set when such a page is opened and cleared by the tab host on its way back
     * to the screen — see [openLibraryPage] and BootScreen.willShow.
     */
    val offTab = MutableStateFlow(false)

    /**
     * Show the search page.
     *
     * [withKeyboard] is set by the header's button, which goes straight to typing;
     * the tab bar's shows the page and its last results instead. The root screen
     * watches [pendingKeyboard] and pushes the editor, because the caller is often
     * a sub-page that is about to be unwound.
     */
    fun openSearch(withKeyboard: Boolean = false) {
        searchActive.value = true
        if (withKeyboard) pendingKeyboard.value = true
    }

    val pendingKeyboard = MutableStateFlow(false)

    /**
     * The library index — the simplified layout's middle button.
     *
     * A state of the shell rather than a screen, because the thing it opens
     * *onto* is a tab, and tabs are states too: there is no stack to push
     * Albums on top of a page with. So the index takes the shell's place the
     * way search does, a tab replaces it when one is chosen, and that tab's
     * header carries a back arrow that brings the index back. A page that is a
     * real screen — a genre, the composers — pushes over the top and leaves
     * this set, so backing out of it lands here too.
     */
    val libraryIndex = MutableStateFlow(false)

    /** Show the index, leaving whatever was showing. */
    fun openLibraryIndex() {
        closeSearch()
        libraryIndex.value = true
    }

    /**
     * Open on the index, once, when the app starts under Simplified.
     *
     * Guarded here rather than by a composition effect's key: the root screen
     * leaves composition whenever a screen is pushed over it and re-enters when
     * that screen pops, so a `LaunchedEffect(Unit)` there runs again on every
     * return. Coming back from the keyboard with a query typed, that landed you
     * on the index with search closed — the results were one tap away and
     * looked like the search had been thrown away.
     */
    private var landed = false

    fun landOnLibraryIndex() {
        if (landed) return
        landed = true
        libraryIndex.value = true
    }

    fun setQuery(query: String) {
        searchQuery.value = query
        searchActive.value = true
    }

    /**
     * Leave the search page, keeping the query.
     *
     * Coming back and finding the last results still there is the point —
     * clearing on the way out meant every visit started from nothing.
     */
    fun closeSearch() {
        searchActive.value = false
    }

    /** Empty the field without leaving the page — what the X in it means. */
    fun clearSearch() {
        searchQuery.value = ""
    }

    /**
     * Go to a tab's front page.
     *
     * Tapping a tab is a request to start over there, so the list returns to the
     * top rather than to wherever it was left — its sort is the only thing that
     * carries over.
     */
    fun selectTab(tab: LibraryTab) {
        closeSearch()
        // Choosing from the index is leaving it — see [libraryIndex].
        libraryIndex.value = false
        // A selection belongs to the list it was made in; changing tabs abandons
        // it rather than leaving a stale count waiting on some other page.
        Selections.clearAll()
        ScrollAnchors.clear("tab:${tab.name.lowercase()}")
        currentTab.value = tab
    }
}

/**
 * Which library page More was opened over.
 *
 * More carries the modifiers — view, sort, filter — of the list underneath it,
 * so it has to be told which list that is. Both ways in already know: the
 * header's corner is drawn by the page itself, and the tab bar's ··· is drawn
 * by that page's own chrome.
 *
 * A page identity rather than the settings themselves. More reads those live
 * when it draws, so a sort changed and stepped back from shows what it now is,
 * rather than the snapshot taken when the page below last composed.
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
    TAG_SONGS,

    /** Peers of the tabs, reached from More, that carry a tab's sort. */
    GENRES,
    COMPOSERS,
    COMPILATIONS,
    DOWNLOADS,
}

/**
 * Push a screen that returns no result. A single-parameter helper so the
 * trailing-lambda call site (`go { SomeScreen(it) }`) reads cleanly — the raw
 * [navigateTo] takes an optional result callback as its last parameter, which
 * would otherwise capture the trailing lambda.
 */
fun SimpleLightScreen<*>.go(factory: (SealedLightActivity) -> SimpleLightScreen<Unit>) {
    navigateTo(factory)
}

/**
 * Open a library page that is a peer of the tabs — a liked list, a genre list,
 * the downloads.
 *
 * These are reached from More, but they are not pages *of* More: More is a way
 * in, the way the tab bar is a way in, and neither is somewhere to go back up
 * to. So the stack is unwound first and the page lands directly on the tab host,
 * exactly one level deep. That is what lets it drop its back button — the bar
 * below is how you leave — while anything opened *from* it is a genuine level
 * deeper and keeps one.
 *
 * [LibraryNav.offTab] is set last, after the push: the unwind makes the tab host
 * current for an instant, and it clears the flag whenever it is shown.
 */
fun SimpleLightScreen<*>.openLibraryPage(factory: (SealedLightActivity) -> SimpleLightScreen<Unit>) {
    popToRoot()
    navigateTo(factory)
    LibraryNav.offTab.value = true
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
 * Open the player from a long-press sheet, taking the sheet off the stack.
 *
 * A sheet is a menu, not a place. Left underneath the player it becomes what the
 * player's way out lands on — and that button means "back to the page I was
 * looking at", which is the list the sheet was opened over, not the sheet.
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
 * What the page being opened should have underneath it — where back leads.
 *
 * Back is "up one level in this tab", not "undo my last move", so opening a
 * library page means saying what is above it. Walking down a tab already
 * answers that: the page you are standing on *is* the parent, and a push is the
 * whole of it. A jump from outside the library — the player's menu, the queue,
 * a search result — has nothing above it worth returning to, so the walk that
 * would have reached the page is laid down first: unwind to the library, choose
 * the tab whose hierarchy the page belongs to, push the ancestors in order.
 * Back then walks that hierarchy for free, the tab bar stays lit the whole way
 * down, and the last step out lands on a tab root with no back button — which is
 * what a parent page is.
 *
 * Pushing a jump on top of wherever you happened to be gives back nothing
 * sensible to do: from Now Playing → Go to Album, backing out led to the artist,
 * and backing out of *that* returned to the album, because the album was still
 * underneath it on the stack.
 *
 * Which of the two applies is the caller's to name rather than something to
 * infer here. Neither rule is safe as a blanket one: laying the ancestors down
 * is right from a tab list and wrong from a genre, a compilation or a playlist,
 * because those are real parents and unwinding to a tab root throws them away.
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

/** Put [parent] on the stack, so that whatever is pushed next sits on top of it. */
private fun SimpleLightScreen<*>.layDown(parent: Parent) {
    if (parent !is Parent.Walk) return
    popToRoot()
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
