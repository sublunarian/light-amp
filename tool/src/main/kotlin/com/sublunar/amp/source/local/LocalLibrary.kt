package com.sublunar.amp.source.local

import android.media.MediaMetadataRetriever
import com.sublunar.amp.source.PhoneAccess
import com.sublunar.amp.source.PhoneScan
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.hasRuntimePermission
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import com.sublunar.amp.data.Album
import com.sublunar.amp.data.PhoneFiles
import com.sublunar.amp.data.Track

/**
 * The phone's own music, read straight off the filesystem.
 *
 * There is no MediaStore here: `android.content` is a blocked import for a
 * tool. A tool *can* hold `READ_MEDIA_AUDIO` and walk the shared music folder
 * with `java.io`, reading tags with [MediaMetadataRetriever], which takes a
 * plain path.
 *
 * Ids are the file's own path: see [PhoneFiles].
 */
object LocalLibrary {

    /**
     * The folder this app claims inside the shared music directory,
     * `Music/<AppName>` as other LP3 music tools do (Reverb reads `Music/Reverb`).
     * Only this folder: the phone's other audio isn't a library, and another
     * tool's folder is that tool's library.
     */
    const val FOLDER = "Music/Amp"

    /** [FOLDER] on the phone's shared storage. */
    internal val MUSIC_DIR = File("/storage/emulated/0", FOLDER)

    /**
     * Where music is looked for. Both are the same directory (`/sdcard` is a
     * symlink to `/storage/emulated/0`), listed twice only because the roots
     * are filtered by which actually resolve, then deduplicated by canonical
     * path, so nothing is scanned twice.
     */
    private val ROOTS = listOf(MUSIC_DIR, File("/sdcard", FOLDER))

    /**
     * What the phone can actually play, by extension. Not everything with
     * audio in it: `ape`, `wv` and `dsf` have no decoder here, and listing them
     * would put tracks in the library that fail the moment they are pressed.
     * `oga`, `mka` and `m4b` are ordinary Ogg, Matroska and MP4 audio under
     * other names.
     */
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "m4a", "m4b", "aac", "ogg", "oga", "opus",
        "wav", "wma", "mp4", "mka", "aiff", "aif",
    )

    /** How deep to walk. Deep enough for Artist/Album/Disc, shallow enough to end. */
    private const val MAX_DEPTH = 6

    /** The permission that makes any of this readable — see lighttool.toml. */
    const val PERMISSION = "android.permission.READ_MEDIA_AUDIO"

    /**
     * Whether the app may actually read the music folder, and if not, why.
     *
     * Not inferred from the filesystem: without the permission, listing the
     * folder returns an *empty* array rather than an error, so a folder we
     * can't see looks exactly like an empty one.
     *
     * The grant, asked of this process, decides. LightOS answers from its own
     * policy before it looks at the grant, so its word counts only when the
     * process can't say, or to tell a block from a missing grant. Both are
     * logged side by side for a report where they disagree.
     */
    suspend fun access(): PhoneAccess {
        val held = hasRuntimePermission(PERMISSION)
        val lightOs = checkPermission(PERMISSION).asKotlinResult
            .map { it.permissionResult }
            .getOrNull()
        Log.i(TAG, "Music access: grant=${held ?: "unknown"} lightos=${lightOs ?: "no answer"}")
        return when {
            held == true -> PhoneAccess.GRANTED
            held == null && lightOs == LightServiceMethod.GetPermission.Result.Granted -> PhoneAccess.GRANTED
            lightOs == LightServiceMethod.GetPermission.Result.BlockedByServer -> PhoneAccess.BLOCKED_BY_LIGHTOS
            else -> PhoneAccess.NOT_GRANTED
        }
    }

    /** Created on demand, so there is somewhere obvious to copy music into. */
    private fun ensureFolder() {
        runCatching { MUSIC_DIR.mkdirs() }
    }

    private fun roots(): List<File> =
        ROOTS.filter { it.isDirectory }.distinctBy { it.canonicalPath }

    /**
     * Walk the folders and read every file's tags, on IO and cancellable
     * between files: a few thousand tracks is a few thousand retriever opens.
     */
    suspend fun scan(onProgress: (Int) -> Unit): PhoneScan = withContext(Dispatchers.IO) {
        ensureFolder()
        val roots = roots()
        val files = mutableListOf<File>()
        roots.forEach { root -> collect(root, 0, files) }
        // "No songs" has two causes, a folder the app can't read and a folder
        // with nothing in it, and they look the same from the library screen.
        Log.i(TAG, "Scanned ${roots.joinToString { it.path }}: ${files.size} files")

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
        PhoneScan(tracks, albumsFrom(tracks))
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

    private fun readTrack(retriever: MediaMetadataRetriever, file: File): Track? = runCatching {
        retriever.setDataSource(file.absolutePath)
        fun tag(key: Int): String = retriever.extractMetadata(key)?.trim().orEmpty()
        // A file with no tags at all is still music: the name is what the Light
        // app shows for it, and the folder is as good an album as any.
        val title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { file.nameWithoutExtension }
        val album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM).ifBlank { file.parentFile?.name.orEmpty() }
        val artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST).ifBlank { UNKNOWN_ARTIST }
        val albumArtist = tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).ifBlank { artist }
        Track(
            id = PhoneFiles.idFor(file),
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            albumId = albumId(albumArtist, album),
            // The file is its own cover: ArtworkLoader reads the embedded
            // picture for any id that points at a path.
            coverArtId = PhoneFiles.idFor(file),
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L,
            trackNumber = tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                .substringBefore('/').trim().toIntOrNull(),
            discNumber = tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                .substringBefore('/').trim().toIntOrNull(),
            year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull(),
            // No server keeps a tally here, and a local one would make Most
            // Played mean something different from every other source.
            playCount = 0,
            lastPlayedMs = 0L,
            genre = tag(MediaMetadataRetriever.METADATA_KEY_GENRE),
            composer = tag(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
            // ReplayGain is always a custom tag, which the retriever can't see.
            gainDb = ReplayGainTags.gainDb(file),
        )
    }.getOrElse {
        Log.w(TAG, "Unreadable audio file: ${file.name}")
        null
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
                    createdMs = group.mapNotNull { PhoneFiles.fileOf(it.id)?.lastModified() }.maxOrNull() ?: 0L,
                )
            }

    private fun albumId(albumArtist: String, album: String): String =
        PhoneFiles.ID_PREFIX + "album/" + albumArtist.lowercase() + "/" + album.lowercase()

    private const val UNKNOWN_ARTIST = "Unknown Artist"
    private const val PROGRESS_EVERY = 25
    private const val TAG = "LocalLibrary"
}
