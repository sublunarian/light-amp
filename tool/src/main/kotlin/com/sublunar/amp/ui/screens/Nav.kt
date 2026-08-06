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
     * Tabs whose *liked* list the user was last looking at.
     *
     * A tab button always lands on that tab's front page — but "the front page"
     * of Songs is Liked Songs for someone who lives there, so which of the two
     * variants was last chosen is the one thing a tab remembers besides its sort.
     */
    private val likedTabs = mutableSetOf<LibraryTab>()

    /** Set true when a liked list is opened, false by its "All …" row. */
    fun setLiked(tab: LibraryTab, liked: Boolean) {
        if (liked) likedTabs += tab else likedTabs -= tab
    }

    fun isLiked(tab: LibraryTab): Boolean = tab in likedTabs

    /**
     * Forget which tabs were on their liked list — for a change of source.
     *
     * Likes belong to a server. Carrying "you were on Liked Albums" across to
     * another one lands you on its liked list instead of its library, and on a
     * server with no likes at all lands you on a page that is empty by
     * definition.
     */
    fun clearLiked() {
        likedTabs.clear()
    }

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

    fun setQuery(query: String) {
        searchQuery.value = query
        searchActive.value = true
    }

    /**
     * Leave the search page, keeping the query.
     *
     * Coming back to search and finding your last results still there is the
     * point — clearing on the way out meant every visit started from nothing.
     * [clearSearch] is the explicit way to empty it.
     */
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
     * top rather than to wherever it was left — the sort, and whether the liked
     * variant was in use, are the only things that carry over.
     */
    /**
     * True only while the tabbed shell is the screen you are looking at.
     *
     * [currentTab] doesn't change when More, search or any sub-page opens on top
     * of it — it is still "the tab you would return to". So it can't on its own
     * tell a second tap on a tab from the first tap after a trip somewhere else,
     * and the two mean opposite things.
     */
    val shellShowing = MutableStateFlow(false)

    fun selectTab(tab: LibraryTab) {
        // Tapping the tab you are already *looking at* is the way back to its
        // main list — otherwise a tab remembered as "liked" had no button that
        // led anywhere but straight back to the liked page. Coming back from
        // More, or from search, is not that: the tab was left showing its liked
        // list and that is what it should still be showing.
        val lookingAtIt = shellShowing.value && !searchActive.value
        if (lookingAtIt && currentTab.value == tab) setLiked(tab, false)
        closeSearch()
        // A selection belongs to the list it was made in; changing tabs abandons
        // it rather than leaving a stale count waiting on some other page.
        Selections.clearAll()
        ScrollAnchors.clear("tab:${tab.name.lowercase()}")
        currentTab.value = tab
    }
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

/** Long-press sheet for a track in a list that supports multi-select. */
fun SimpleLightScreen<*>.openTrackActions(trackId: String, selection: SelectionState?) {
    go { TrackActionsScreen(it, trackId, onSelect = selection?.let { s -> { s.begin(trackId) } }) }
}
