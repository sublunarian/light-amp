package com.sublunar.amp.data

import kotlinx.serialization.Serializable

/**
 * What the library home's Liked and Downloaded pages, and the lists' Liked and
 * Downloaded switches, have to offer: whether there is anything liked of each
 * kind, anything downloaded, and the two together, and the genres. Worked out
 * as the library changes (LibraryRepository.holdings), and kept per source
 * (AppSettings.lastHoldings), so a page opens already knowing, even at launch
 * before the library has been read.
 */
@Serializable
data class Holdings(
    /** Every genre the library's songs carry, in name order: the Genres page, and whether there is one. */
    val genres: List<String> = emptyList(),
    val likedArtists: Boolean = false,
    val likedAlbums: Boolean = false,
    val likedSongs: Boolean = false,
    /** A song downloaded, and so its album and artist with it. */
    val downloads: Boolean = false,
    val likedDownloadedArtists: Boolean = false,
    val likedDownloadedAlbums: Boolean = false,
    val likedDownloadedSongs: Boolean = false,
    /** The genres the downloads carry, for Downloaded's Genres. */
    val downloadedGenres: List<String> = emptyList(),
) {
    /** Whether this would take away a row or list that [shown] offers: Genres, Liked or Downloaded, or one of theirs. */
    fun hidesAnyOf(shown: Holdings): Boolean =
        (shown.genres.isNotEmpty() && genres.isEmpty()) ||
            (shown.likedArtists && !likedArtists) ||
            (shown.likedAlbums && !likedAlbums) ||
            (shown.likedSongs && !likedSongs) ||
            (shown.downloads && !downloads) ||
            (shown.likedDownloadedArtists && !likedDownloadedArtists) ||
            (shown.likedDownloadedAlbums && !likedDownloadedAlbums) ||
            (shown.likedDownloadedSongs && !likedDownloadedSongs) ||
            (shown.downloadedGenres.isNotEmpty() && downloadedGenres.isEmpty())

    companion object {
        /**
         * From the library as the lists see it. An album or artist counts as
         * downloaded with any one of its songs, the rule the Downloaded lists follow.
         */
        fun of(songs: List<Track>, albums: List<Album>, artists: List<Artist>, downloaded: Set<String>): Holdings {
            val onPhone = if (downloaded.isEmpty()) emptyList() else songs.filter { it.id in downloaded }
            val albumIds = onPhone.mapNotNullTo(HashSet()) { it.albumId }
            val artistNames = onPhone.flatMapTo(HashSet()) { it.albumArtistNames() }
            return Holdings(
                genres = tagValuesIn(songs) { splitTagValues(it.genre) },
                likedArtists = artists.any { it.liked },
                likedAlbums = albums.any { it.liked },
                likedSongs = songs.any { it.liked },
                downloads = onPhone.isNotEmpty(),
                likedDownloadedArtists = artists.any { it.liked && it.name in artistNames },
                likedDownloadedAlbums = albums.any { it.liked && it.id in albumIds },
                likedDownloadedSongs = onPhone.any { it.liked },
                downloadedGenres = tagValuesIn(onPhone) { splitTagValues(it.genre) },
            )
        }
    }
}
