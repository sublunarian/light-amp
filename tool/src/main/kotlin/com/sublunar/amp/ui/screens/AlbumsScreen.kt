package com.sublunar.amp.ui.screens

import androidx.compose.runtime.getValue
import com.sublunar.amp.data.AlbumSort

fun albumSortLabel(sort: AlbumSort): String = when (sort) {
    AlbumSort.TITLE -> "Title"
    // Labels say exactly what the key is: this orders by album artist, not by
    // whoever is credited on a given track, and by the full release date.
    AlbumSort.ARTIST -> "Album Artist"
    AlbumSort.YEAR -> "Date Released"
    AlbumSort.DATE_ADDED -> "Recently Added"
    AlbumSort.RECENTLY_PLAYED -> "Recently Played"
    AlbumSort.MOST_PLAYED -> "Plays"
    AlbumSort.RATING -> "Rating"
    AlbumSort.RANDOM -> "Random"
}
