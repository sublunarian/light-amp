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
    /** The folder the music is read from, as the screens name it. */
    val folderLabel: String

    /** The permission that makes the folder readable — see lighttool.toml. */
    val permission: String

    /** Whether the app may read the folder now, and if not, why. */
    suspend fun access(): PhoneAccess

    /** Every track and album in the folder, with [onProgress] told the count as it goes. */
    suspend fun scan(onProgress: (Int) -> Unit): PhoneScan

    /** The phone's own playlists, kept as files beside the music. */
    val playlists: PhonePlaylists
}

/** What stands between the app and the music folder, if anything. */
enum class PhoneAccess { GRANTED, NOT_GRANTED, BLOCKED_BY_LIGHTOS }

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
