package com.sublunar.amp.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.sublunar.amp.data.SongSort

fun songSortLabel(sort: SongSort): String = when (sort) {
    SongSort.TITLE -> "Title"
    SongSort.ARTIST -> "Artist"
    SongSort.DATE_ADDED -> "Recently Added"
    SongSort.RECENTLY_PLAYED -> "Recently Played"
    SongSort.MOST_PLAYED -> "Plays"
    SongSort.RATING -> "Rating"
}
