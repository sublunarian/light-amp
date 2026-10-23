package com.sublunar.amp.ui.screens

import com.sublunar.amp.data.AlbumSort

fun albumSortLabel(sort: AlbumSort): String = when (sort) {
    AlbumSort.TITLE -> "Title"
    // Labels name the key exactly: the album artist, and the full release date.
    AlbumSort.ARTIST -> "Album Artist"
    AlbumSort.YEAR -> "Date Released"
    AlbumSort.DATE_ADDED -> "Recently Added"
    AlbumSort.RECENTLY_PLAYED -> "Recently Played"
    AlbumSort.MOST_PLAYED -> "Frequently Played"
    AlbumSort.RATING -> "Rating"
    AlbumSort.RANDOM -> "Random"
}
