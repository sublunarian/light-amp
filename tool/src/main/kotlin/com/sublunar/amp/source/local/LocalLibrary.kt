package com.sublunar.amp.source.local

import com.sublunar.amp.data.ToolManagerFolders
import android.media.MediaMetadataRetriever
import com.sublunar.amp.log.Log
import com.sublunar.amp.log.logName
import com.sublunar.amp.source.PhoneFavorites
import com.sublunar.amp.source.PhoneLibrary
import com.sublunar.amp.source.PhonePlaylists
import com.sublunar.amp.source.PhoneScan
import com.sublunar.amp.source.PhoneAccess
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import com.sublunar.amp.data.Album
import com.sublunar.amp.data.ReleaseType
import com.sublunar.amp.data.PhoneFiles
import com.sublunar.amp.data.Track

/**
 * The phone's own music, read straight off the filesystem: the Music folder a
 * computer fills through Light's Tool Manager (see [ToolManagerFolders]), which
 * is Amp's own storage and needs no permission, and, for now, `Music/Amp`,
 * which needs the music permission. `Music/Amp` goes once the Tool Manager is
 * finished; until then a library copied there keeps working.
 *
 * There is no MediaStore here: `android.content` is a blocked import for a
 * tool, and the phone's media index never looks at a tool's own files. Tags
 * are read with [MediaMetadataRetriever], which takes a plain path.
 *
 * Ids are the file's own path: see [PhoneFiles].
 */
object LocalLibrary : PhoneLibrary {

    override val label: String get() = "On This Phone"

    override val permission: String get() = "android.permission.READ_MEDIA_AUDIO"

    /** The shared folder read until the Tool Manager is finished: `Music/<AppName>`, as other LP3 music tools do. */
    private const val FOLDER = "Music/Amp"

    /** [FOLDER] on the phone's shared storage. */
    internal val MUSIC_DIR = File("/storage/emulated/0", FOLDER)

    /**
     * Where music is looked for. `/sdcard` is a symlink to
     * `/storage/emulated/0`, listed only because the roots are filtered by
     * which actually resolve, then deduplicated by canonical path, so nothing
     * is scanned twice.
     */
    private val SHARED_ROOTS = listOf(MUSIC_DIR, File("/sdcard", FOLDER))

    override val playlists: PhonePlaylists get() = LocalPlaylists

    override val favorites: PhoneFavorites by lazy { FavoritesFile(LocalPlaylists.folder()) }

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

    private fun roots(): List<File> =
        (SHARED_ROOTS + listOfNotNull(ToolManagerFolders.musicDir)).filter { it.isDirectory }.distinctBy { it.canonicalPath }

    /**
     * Whether `Music/Amp` can be read, and if not, why. Without a `Music/Amp`
     * there is nothing to ask for. Without the permission, listing the folder
     * returns an *empty* array rather than an error, so any song seen in it
     * proves access; otherwise LightOS is asked, and it can answer from its
     * own policy rather than the grant, which is why a song seen wins.
     */
    override suspend fun access(): PhoneAccess = withContext(Dispatchers.IO) {
        if (!MUSIC_DIR.isDirectory) return@withContext PhoneAccess.GRANTED
        if (hasSong(MUSIC_DIR, 0)) return@withContext PhoneAccess.GRANTED
        val lightOs = checkPermission(permission).asKotlinResult
            .map { it.permissionResult }
            .getOrNull()
        Log.i(TAG, "Music/Amp access: lightos=${lightOs ?: "no answer"}")
        when (lightOs) {
            LightServiceMethod.GetPermission.Result.Granted -> PhoneAccess.GRANTED
            LightServiceMethod.GetPermission.Result.BlockedByServer -> PhoneAccess.BLOCKED_BY_LIGHTOS
            else -> PhoneAccess.NOT_GRANTED
        }
    }

    private fun hasSong(dir: File, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false
        return dir.listFiles().orEmpty().any { entry ->
            when {
                entry.name.startsWith(".") -> false
                entry.isDirectory -> hasSong(entry, depth + 1)
                else -> entry.extension.lowercase() in AUDIO_EXTENSIONS
            }
        }
    }

    /**
     * The Tool Manager's folder by its dates; `Music/Amp` only by whether a song
     * can be seen there now and could at the last scan. That flips when access
     * is granted, and the scan that ran before it, seeing nothing, mustn't stand
     * in for one that can. Music copied into `Music/Amp` otherwise waits for
     * Rescan or the next scan, as before.
     */
    override fun changedSince(ms: Long): Boolean =
        ToolManagerFolders.changedSince(ms) || hasSong(MUSIC_DIR, 0) != (sawSharedMarker?.exists() == true)

    /** Present when the last scan found songs in `Music/Amp`. */
    private val sawSharedMarker: File? get() = ToolManagerFolders.privateFile("music-amp-seen")

    /**
     * Walk the folders and read every file's tags, on IO and cancellable
     * between files: a few thousand tracks is a few thousand retriever opens.
     */
    override suspend fun scan(onProgress: (Int) -> Unit): PhoneScan = withContext(Dispatchers.IO) {
        val startedMs = System.currentTimeMillis()
        val roots = roots()
        val files = mutableListOf<File>()
        roots.forEach { root -> collect(root, 0, files) }
        val shared = MUSIC_DIR.canonicalFile
        val sawShared = files.any { it.canonicalFile.startsWith(shared) }
        sawSharedMarker?.let { runCatching { if (sawShared) it.createNewFile() else it.delete() } }
        Log.i(TAG, "scan: ${roots.size} music folder(s), ${files.size} audio files")

        val retriever = MediaMetadataRetriever()
        val tracks = mutableListOf<Track>()
        val modifiedMs = mutableMapOf<String, Long>()
        val releaseTypes = mutableMapOf<String, ReleaseType>()
        val unreadable = mutableMapOf<String, Int>()
        try {
            files.forEachIndexed { index, file ->
                coroutineContext.ensureActive()
                readTrack(retriever, file, unreadable)?.let { (track, releaseType) ->
                    tracks += track
                    modifiedMs[track.id] = file.lastModified()
                    releaseTypes[track.id] = releaseType
                }
                if (index % PROGRESS_EVERY == 0) onProgress(index)
            }
        } finally {
            runCatching { retriever.release() }
        }
        val albums = albumsFrom(tracks, modifiedMs, releaseTypes)
        Log.i(
            TAG,
            "scan done: ${tracks.size} tracks, ${albums.size} albums in ${System.currentTimeMillis() - startedMs} ms" +
                if (unreadable.isEmpty()) "" else "; unreadable: " + unreadable.entries.joinToString { "${it.value} ${it.key}" },
        )
        PhoneScan(tracks, albums)
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

    /**
     * The file's track, with the release type its tags give its album. A file
     * that can't be read is counted in [unreadable] by its kind and failure:
     * its name is a song's.
     */
    private fun readTrack(
        retriever: MediaMetadataRetriever,
        file: File,
        unreadable: MutableMap<String, Int>,
    ): Pair<Track, ReleaseType>? = runCatching {
        retriever.setDataSource(file.absolutePath)
        fun tag(key: Int): String = retriever.extractMetadata(key)?.trim().orEmpty()
        // A file with no tags at all is still music: the name is what the Light
        // app shows for it, and the folder is as good an album as any.
        val title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { file.nameWithoutExtension }
        val album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM).ifBlank { file.parentFile?.name.orEmpty() }
        val artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST).ifBlank { UNKNOWN_ARTIST }
        val albumArtist = tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).ifBlank { artist }
        // ReplayGain and the release type are custom tags, which the retriever can't see.
        val custom = CustomTags.read(file)
        val releaseType = CustomTags.releaseType(
            custom,
            compilation = tag(MediaMetadataRetriever.METADATA_KEY_COMPILATION) == "1",
        )
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
            year = CustomTags.year(custom)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull(),
            // No server keeps a tally here, and a local one would make Most
            // Played mean something different from every other source.
            playCount = 0,
            lastPlayedMs = 0L,
            genre = tag(MediaMetadataRetriever.METADATA_KEY_GENRE),
            composer = tag(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
            gainDb = CustomTags.gainDb(custom),
        ) to releaseType
    }.getOrElse {
        unreadable.merge("${file.extension.lowercase()} (${it.logName()})", 1, Int::plus)
        null
    }

    /**
     * Albums are derived from the tags, the way the Artists list already is.
     * [modifiedMs] is each track's file date, [releaseTypes] what its tags say
     * its album is: the most specific any of the album's songs says.
     */
    private fun albumsFrom(
        tracks: List<Track>,
        modifiedMs: Map<String, Long>,
        releaseTypes: Map<String, ReleaseType>,
    ): List<Album> =
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
                    createdMs = group.mapNotNull { modifiedMs[it.id] }.maxOrNull() ?: 0L,
                    releaseType = group.mapNotNull { releaseTypes[it.id] }
                        .filter { it != ReleaseType.ALBUM }
                        .minByOrNull { it.specificity } ?: ReleaseType.ALBUM,
                )
            }

    override suspend fun taggedLyrics(trackId: String): String? = withContext(Dispatchers.IO) {
        PhoneFiles.fileOf(trackId)?.let { CustomTags.lyrics(CustomTags.read(it)) }
    }

    internal fun albumId(albumArtist: String, album: String): String =
        PhoneFiles.ID_PREFIX + "album/" + albumArtist.lowercase() + "/" + album.lowercase()

    private const val UNKNOWN_ARTIST = "Unknown Artist"
    private const val PROGRESS_EVERY = 25
    private const val TAG = "AmpLocal"
}
