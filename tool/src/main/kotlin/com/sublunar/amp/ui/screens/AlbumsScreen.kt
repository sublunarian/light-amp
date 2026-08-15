package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.data.AlbumSort
import com.sublunar.amp.data.sortAlbums
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.EmptyState
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.SplitActionRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.sublunar.amp.ui.components.AlbumGrid
import com.sublunar.amp.ui.components.TrackRow
import com.sublunar.amp.ui.components.ScrollableList
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen

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
