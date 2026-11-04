package com.sublunar.amp.data

/**
 * What the chosen libraries show: [selected] (null for all of them), and never
 * one of [hidden]. The Songs tab goes by it; a playlist by [hidden] alone.
 *
 * A song belongs to its album's library. An album with no library recorded
 * shows everywhere, and so does a song with no album.
 */
internal data class LibraryView(val selected: Set<String>?, val hidden: Set<String>) {

    /** Whether anything is left out at all. */
    val narrows: Boolean get() = selected != null || hidden.isNotEmpty()

    fun showsAlbum(libraryId: String?): Boolean =
        (selected == null || libraryId == null || libraryId in selected) &&
            (libraryId == null || libraryId !in hidden)

    /**
     * [hasAlbum] is whether the album is in the library at all. A song whose
     * album isn't synced yet can't be placed, so a narrowed view leaves it out,
     * as the Songs tab does.
     */
    fun showsSong(albumId: String?, hasAlbum: Boolean, libraryId: String?): Boolean =
        !narrows || albumId == null || (hasAlbum && showsAlbum(libraryId))
}

/**
 * Whether a playlist of [ids] gets the download mark: at least one song is
 * shown, and every shown song is downloaded. [shown] is null when every song is.
 */
internal fun playlistMarked(ids: List<String>, downloaded: Set<String>, shown: Set<String>?): Boolean {
    val inView = if (shown == null) ids else ids.filter { it in shown }
    return inView.isNotEmpty() && inView.all { it in downloaded }
}
