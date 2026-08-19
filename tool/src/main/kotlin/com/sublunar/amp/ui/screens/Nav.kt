package com.sublunar.amp.ui.screens

import kotlinx.coroutines.launch
import com.sublunar.amp.data.LastSection
import com.sublunar.amp.App
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
     * Show the search page.
     *
     * [withKeyboard] is set by the field at the top of every library list, which
     * goes straight to typing; the tab bar's button shows the page and its last
     * results instead.
     */
    fun openSearch(withKeyboard: Boolean = false) {
        searchActive.value = true
        record(LastSection.SEARCH)
        typing.value = withKeyboard
    }

    /**
     * Whether the keyboard is up.
     *
     * Search has two states on one page rather than two screens: typing, with the
     * keyboard against the bottom of the screen and the results filling what is
     * left, and reading, with the keyboard gone and the nav bar back. Return
     * moves from the first to the second, and the results never go away in
     * between — which is the whole reason this isn't the SDK's full-screen
     * editor, where the list is behind the keyboard rather than above it.
     */
    val typing = MutableStateFlow(false)

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
     * Back to the library as you left it — the bar's middle button.
     *
     * Deliberately not [openLibraryIndex]: the button means "the library",
     * and the library is whichever page you were last reading, scrolled where
     * you left it, not a menu you have to walk through again. Only a cold start
     * opens on the index, because then there is no page to go back to. The way
     * *up* to the index is the back arrow in a tab's header.
     *
     * So this only drops search, if that is what is covering the library; the
     * tab and every scroll position are left exactly as they were.
     */
    fun returnToLibrary() {
        closeSearch()
    }

    /**
     * The bar's middle button, pressed while the shell itself is showing.
     *
     * One press is "the library" and lands on the page you were last reading.
     * A second, now that you are already there, is a press with nowhere left to
     * go — so it means the step up, to the index. Pressing again comes back
     * down to the page, which makes the button a way between the two rather
     * than a dead tap on one of them.
     *
     * Search is the exception and is only ever left, never toggled into: it
     * covers the library rather than being part of it, so the first press out
     * of it is the plain "back to the library" above.
     */
    fun pressLibrary() {
        // Not on the library: come back to it, as you left it.
        if (searchActive.value) {
            closeSearch()
            return
        }
        // Already on it: go to the top. Not a toggle — a second press used to
        // flip back down to the page you had just come up from, so the button
        // undid itself and never settled anywhere. From the list it now does
        // nothing you can see, which is the honest answer to "take me to the
        // list" when the list is what you are looking at.
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

    /**
     * True once, for whoever settles the opening destination.
     *
     * The caller decides what that destination is — see BootScreen, which
     * restores whichever of the bar's three the app was last left on.
     */
    fun claimFirstLanding(): Boolean {
        if (landed) return false
        landed = true
        return true
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
        typing.value = false
        record(LastSection.LIBRARY)
    }

    /**
     * Note where the app is, so it can reopen there.
     *
     * Silent until the app has booted: these run from navigation that can fire
     * before the settings store exists, and where the app reopens is not worth
     * a crash on the way in.
     */
    internal fun record(section: LastSection) {
        if (!App.isReady) return
        App.scope.launch { App.settings.setLastSection(section) }
    }

        /**
     * Go to a tab's front page.
     *
     * Tapping a tab is a request to start over there, so the list returns to the
     * top rather than to wherever it was left — its sort is the only thing that
     * carries over.
     */
    /**
     * The bar's tab, tapped.
     *
     * Tapping the one you are already on is not a request to go there — you are
     * there — so it means the other thing a tab button can mean: back to the top
     * of it. Drilling in is already handled by the bar on the pushed page, which
     * pops to the root before it selects.
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

    /** Peers of the tabs, reached from More, that carry a tab's sort. */
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
 * is right from a tab list and wrong from a genre or a playlist,
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
