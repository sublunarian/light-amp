package com.sublunar.amp.data

import android.media.MediaMetadataRetriever
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * The phone's own music: the files the Light Music app plays, read straight off
 * the filesystem.
 *
 * There is no MediaStore here — `android.content` is a blocked import for a
 * tool, so the library can't be queried the usual way. What a tool *can* do is
 * hold `READ_MEDIA_AUDIO` (it's on the SDK's allowed list) and walk the shared
 * music folder with `java.io`, reading tags with [MediaMetadataRetriever], which
 * takes a plain path and needs no context. That is exactly what this does.
 *
 * Ids are the file's own path behind [ID_PREFIX], so they're stable across
 * rescans without a database of their own, and playback can turn any track back
 * into a file without a lookup.
 */
object LocalLibrary {

    /** Marks an id as a path on this phone rather than something from a server. */
    const val ID_PREFIX = "file:"

    fun idFor(file: File): String = ID_PREFIX + file.absolutePath

    fun pathOf(id: String): String? =
        if (id.startsWith(ID_PREFIX)) id.removePrefix(ID_PREFIX) else null

    fun fileOf(id: String): File? = pathOf(id)?.let { File(it) }.takeIf { it?.isFile == true }

    fun isLocal(id: String): Boolean = id.startsWith(ID_PREFIX)

    /**
     * The folder this app claims inside the shared music directory.
     *
     * `Music/<AppName>`, which is what other LP3 music tools do — Reverb reads
     * `/storage/emulated/0/Music/Reverb`. Following the same shape means anyone
     * who has loaded music onto one of these phones before already knows where
     * this goes, and two tools can sit side by side without fighting over a
     * folder.
     */
    const val FOLDER = "Music/Amp"

    /**
     * Where music lands.
     *
     * A folder of our own inside the shared Music directory rather than all of
     * it: the phone's other audio (ringtones, voice notes, whatever another app
     * has left lying about) isn't a library, and a player that swept the lot up
     * would be listing things nobody chose to put in it.
     *
     * This folder only. Another tool's folder is that tool's library, and
     * silently absorbing it means the user can't tell what they put here from
     * what something else did — so anything Amp is to play gets copied in
     * deliberately.
     *
     * Both entries are the same directory: `/sdcard` is a symlink to
     * `/storage/emulated/0`, listed twice only because the roots are filtered by
     * which actually resolve. They are deduplicated by canonical path below, so
     * nothing is scanned or listed twice.
     */
    private val ROOTS = listOf(
        "/storage/emulated/0/$FOLDER",
        "/sdcard/$FOLDER",
    )

    /** Created on demand, so the folder exists to be copied into. */
    fun ensureFolder() {
        runCatching { File("/storage/emulated/0/$FOLDER").mkdirs() }
    }

    private val AUDIO_EXTENSIONS =
        setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "mp4", "aiff", "aif")

    /** How deep to walk. Deep enough for Artist/Album/Disc, shallow enough to end. */
    private const val MAX_DEPTH = 6

    data class Scan(val tracks: List<Track>, val albums: List<Album>)

    /** The permission that makes any of this readable — see lighttool.toml. */
    const val PERMISSION = "android.permission.READ_MEDIA_AUDIO"

    /**
     * Whether the app may actually read the music folder.
     *
     * Asked of LightOS rather than inferred from the filesystem: without the
     * permission, listing `/sdcard/Music` returns an *empty* array rather than
     * null or an error, so a folder full of music and a folder we aren't allowed
     * to see look exactly alike from here — which is how a local library ends up
     * silently showing nothing.
     */
    suspend fun permitted(): Boolean =
        checkPermission(PERMISSION).asKotlinResult
            .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
            .getOrDefault(false)

    private fun roots(): List<File> =
        ROOTS.map(::File).filter { it.isDirectory }.distinctBy { it.canonicalPath }

    /**
     * Walk the folders and read every file's tags.
     *
     * On the IO dispatcher and cancellable between files: a few thousand tracks
     * is a few thousand retriever opens, and the user may well walk away from the
     * page that started it.
     */
    suspend fun scan(onProgress: (Int) -> Unit = {}): Scan = withContext(Dispatchers.IO) {
        // Made once access is granted, so there is somewhere obvious to copy
        // music into rather than a folder the user has to know to create.
        ensureFolder()
        val files = mutableListOf<File>()
        roots().forEach { root -> collect(root, 0, files) }
        // Logged because "no songs" has two very different causes — a folder the
        // app can't read and a folder with nothing in it — and they look the same
        // from the library screen.
        Log.i(TAG, "Scanned ${roots().joinToString { it.path }}: ${files.size} files")

        val retriever = MediaMetadataRetriever()
        val tracks = mutableListOf<Track>()
        try {
            files.forEachIndexed { index, file ->
                coroutineContext.ensureActive()
                readTrack(retriever, file)?.let(tracks::add)
                if (index % PROGRESS_EVERY == 0) onProgress(index)
            }
        } finally {
            runCatching { retriever.release() }
        }
        Scan(tracks, albumsFrom(tracks))
    }

    private fun collect(dir: File, depth: Int, into: MutableList<File>) {
        if (depth > MAX_DEPTH) return
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            when {
                // Hidden folders are caches and thumbnails, never someone's music.
                entry.name.startsWith(".") -> Unit
                entry.isDirectory -> collect(entry, depth + 1, into)
                entry.extension.lowercase() in AUDIO_EXTENSIONS -> into += entry
            }
        }
    }

    private fun readTrack(retriever: MediaMetadataRetriever, file: File): Track? {
        val tags = runCatching {
            retriever.setDataSource(file.absolutePath)
            Tags(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                disc = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR),
                // The tag pages are offered only where the library has the tag
                // (see LibraryIndex), and nothing read these — so a phone full
                // of tagged files still reported having no genres and no
                // composers, and neither page could ever appear.
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
            )
        }.getOrElse {
            Log.w(TAG, "Unreadable audio file: ${file.name}")
            null
        } ?: return null

        // A file with no tags at all is still music: the name is what the Light
        // app shows for it, and the folder is as good an album as any.
        val title = tags.title?.trim().orEmpty().ifBlank { file.nameWithoutExtension }
        val album = tags.album?.trim().orEmpty().ifBlank { file.parentFile?.name.orEmpty() }
        val artist = tags.artist?.trim().orEmpty().ifBlank { UNKNOWN_ARTIST }
        val albumArtist = tags.albumArtist?.trim().orEmpty().ifBlank { artist }

        return Track(
            id = idFor(file),
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            albumId = albumId(albumArtist, album),
            // The file is its own cover: see ArtworkLoader, which reads the
            // embedded picture for any id that points at a path.
            coverArtId = idFor(file),
            durationMs = tags.durationMs,
            trackNumber = tags.track?.substringBefore('/')?.trim()?.toIntOrNull(),
            discNumber = tags.disc?.substringBefore('/')?.trim()?.toIntOrNull(),
            year = tags.year?.take(4)?.toIntOrNull(),
            // Nothing counts plays here: there is no server to keep the tally,
            // and inventing one locally would make Most Played mean something
            // different from what it means on every other source.
            playCount = 0,
            lastPlayedMs = 0L,
            genre = tags.genre?.trim().orEmpty(),
            composer = tags.composer?.trim().orEmpty(),
        )
    }

    /** Albums are derived from the tags, the way the Artists list already is. */
    private fun albumsFrom(tracks: List<Track>): List<Album> =
        tracks.groupBy { it.albumId }
            .mapNotNull { (id, group) ->
                if (id == null) return@mapNotNull null
                val first = group.first()
                Album(
                    id = id,
                    title = first.album,
                    artist = first.albumArtist,
                    coverArtId = group.firstNotNullOfOrNull { it.coverArtId },
                    durationMs = group.sumOf { it.durationMs },
                    songCount = group.size,
                    year = group.firstNotNullOfOrNull { it.year },
                    releaseDate = 0L,
                    // Newest file in the album, so "Recently Added" means what it
                    // does everywhere else.
                    createdMs = group.mapNotNull { fileOf(it.id)?.lastModified() }.maxOrNull() ?: 0L,
                )
            }

    private fun albumId(albumArtist: String, album: String): String =
        ID_PREFIX + "album/" + albumArtist.lowercase() + "/" + album.lowercase()

    private data class Tags(
        val title: String?,
        val artist: String?,
        val albumArtist: String?,
        val album: String?,
        val track: String?,
        val disc: String?,
        val year: String?,
        val genre: String?,
        val composer: String?,
        val durationMs: Long,
    )

    private const val UNKNOWN_ARTIST = "Unknown Artist"
    private const val PROGRESS_EVERY = 25
    private const val TAG = "LocalLibrary"
}
