package com.sublunar.amp.source

import com.sublunar.amp.data.Album
import com.sublunar.amp.data.Playlist
import com.sublunar.amp.data.Track

/**
 * Music that is files on this phone: nothing to sign in to, no client, and
 * read by a scan rather than a sync.
 *
 * Not a MusicServer, deliberately. Having no client is what the reachability
 * check, the queue restore, covers and downloads all read as nothing to ask
 * over the network, and the playlists differ in what failing means: a file
 * that wasn't written is a failed write, where a server's missing answer to a
 * create is a success. So the library keeps one branch for music on the
 * phone, keyed on there being one of these rather than on a kind.
 */
interface PhoneLibrary {
    /** Where the music is, as the screens name it: in place of a server's library name or address. */
    val label: String

    /** Whether files came, went or changed after [ms], so a scan would find something new. Touches the disk. */
    fun changedSince(ms: Long): Boolean

    /** Every track and album in the folder, with [onProgress] told the count as it goes. */
    suspend fun scan(onProgress: (Int) -> Unit): PhoneScan

    /** The lyrics tagged into [trackId]'s file, as written there, or null. */
    suspend fun taggedLyrics(trackId: String): String?

    /** The phone's own playlists, kept as files beside the music. */
    val playlists: PhonePlaylists

    /** What is liked here, kept in a file that can leave the phone with the music. */
    val favorites: PhoneFavorites
}

/** The songs, albums and artists liked on this phone. */
data class PhoneLikes(
    val songIds: Set<String>,
    val albumIds: Set<String>,
    val artistNames: Set<String>,
)

/** Likes kept as a file on this phone. Each change answers whether it reached the file. */
interface PhoneFavorites {
    /**
     * [block] with what is saved, null if it can't be read. A like made
     * meanwhile waits for [block] to finish, so rows rebuilt inside it can't
     * miss one.
     */
    suspend fun <T> whileHeld(block: suspend (PhoneLikes?) -> T): T

    suspend fun setSong(track: Track, liked: Boolean): Boolean

    suspend fun setAlbum(album: Album, liked: Boolean): Boolean

    suspend fun setArtist(name: String, liked: Boolean): Boolean
}

/** What a scan of the phone's music folder found. */
data class PhoneScan(val tracks: List<Track>, val albums: List<Album>)

/**
 * Playlists that are files on this phone. Each change answers whether it
 * reached the file — the only way one of these fails.
 */
interface PhonePlaylists {
    suspend fun list(): List<Playlist>

    suspend fun trackIds(id: String): List<String>

    /** The new playlist's id, or null if it couldn't be written. */
    suspend fun create(name: String, trackIds: List<String>): String?

    suspend fun rename(id: String, name: String): Boolean

    suspend fun delete(id: String): Boolean

    suspend fun add(id: String, trackId: String): Boolean

    suspend fun removeAt(id: String, at: Map<Int, String>): Boolean

    suspend fun reorder(id: String, orderedIds: List<String>): Boolean
}
