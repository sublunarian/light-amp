package com.sublunar.amp.source.local

import android.util.Log
import com.sublunar.amp.source.PhonePlaylists
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sublunar.amp.data.PhoneFiles
import com.sublunar.amp.data.Playlist
import com.sublunar.amp.data.isReorderOf

/**
 * Playlists for the phone's own music, kept as `.m3u8` files in
 * `Music/Amp/Playlists`, the format every other music player already reads.
 * A file another app or adb put there is listed, but scoped storage leaves it
 * read-only to Amp.
 *
 * Entries are absolute paths, which is also exactly what a track id is once
 * [PhoneFiles.ID_PREFIX] is taken off, so the mapping in both directions is a
 * string operation that never needs the library loaded.
 *
 * Everything here touches the disk, so it all runs on [Dispatchers.IO].
 */
object LocalPlaylists : PhonePlaylists {

    /** Marks a playlist id as a file on this phone. */
    private const val ID_PREFIX = "m3u:"

    /** What a playlist file is called. `.m3u8` rather than `.m3u` says UTF-8. */
    private const val EXTENSION = ".m3u8"

    private val UNSAFE_IN_NAME = Regex("""[/\\:*?"<>|]""")

    private const val TAG = "LocalPlaylists"

    private fun folder(): File = File(LocalLibrary.MUSIC_DIR, "Playlists")

    private fun idFor(file: File): String = ID_PREFIX + file.absolutePath

    private fun fileOf(id: String): File? =
        if (id.startsWith(ID_PREFIX)) File(id.removePrefix(ID_PREFIX)) else null

    /**
     * A name safe to be a filename. The name *is* the filename, with no sidecar
     * recording the real one, so anything the filesystem would choke on goes.
     */
    private fun fileNameFor(name: String): String {
        val cleaned = name.trim().replace(UNSAFE_IN_NAME, "_").take(120)
        return (cleaned.ifBlank { "Playlist" }) + EXTENSION
    }

    override suspend fun list(): List<Playlist> = withContext(Dispatchers.IO) {
        val files = folder().listFiles()?.filter {
            it.isFile && it.name.endsWith(EXTENSION, ignoreCase = true)
        }.orEmpty()
        files.sortedBy { it.name.lowercase() }.map { file ->
            val ids = readIds(file)
            Playlist(
                id = idFor(file),
                name = file.name.removeSuffix(EXTENSION),
                // The first track's own file: the row gets its embedded cover.
                coverArtId = ids.firstOrNull(),
                createdAt = file.lastModified(),
                updatedAt = file.lastModified(),
                trackIds = ids,
            )
        }
    }

    override suspend fun trackIds(id: String): List<String> = withContext(Dispatchers.IO) {
        readIds(fileOf(id) ?: return@withContext emptyList())
    }

    /** Returns the new playlist's id, or null if it couldn't be written. */
    override suspend fun create(name: String, trackIds: List<String>): String? =
        withContext(Dispatchers.IO) {
            val dir = folder()
            if (!dir.isDirectory && !dir.mkdirs()) {
                Log.w(TAG, "couldn't create ${dir.absolutePath}")
                return@withContext null
            }
            // A name already in use gets a suffix rather than silently replacing
            // someone's playlist.
            var file = File(dir, fileNameFor(name))
            var n = 2
            while (file.exists()) {
                file = File(dir, fileNameFor("${name.trim()} $n"))
                n++
            }
            if (!writeIds(file, trackIds)) return@withContext null
            idFor(file)
        }

    /**
     * Whether the playlist has the name now. False when another playlist
     * already has it: the name is the file, and taking it would replace that one.
     * The phone's storage ignores case, so a name that differs only in case
     * finds this playlist's own file, and isn't another's.
     */
    override suspend fun rename(id: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val file = fileOf(id) ?: return@withContext false
        if (!file.isFile) return@withContext false
        val target = File(file.parentFile, fileNameFor(name))
        if (target.name == file.name) return@withContext true
        if (!target.name.equals(file.name, ignoreCase = true) && target.exists()) return@withContext false
        file.renameTo(target).also { if (!it) Log.w(TAG, "couldn't rename ${file.name}") }
    }

    /** Whether the playlist is gone, counting one another app or adb already removed. */
    override suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val file = fileOf(id) ?: return@withContext false
        if (!file.exists()) return@withContext true
        file.delete().also { if (!it) Log.w(TAG, "couldn't delete ${file.name}") }
    }

    /** Whether the song reached the file. */
    override suspend fun add(id: String, trackId: String): Boolean = withContext(Dispatchers.IO) {
        val file = fileOf(id) ?: return@withContext false
        if (!file.isFile) return@withContext false
        // Duplicates on purpose: the same song twice is a decision someone might mean.
        writeIds(file, readIds(file) + trackId)
    }

    /** Whether the edit reached the file — callers take it back off the screen if it didn't. */
    override suspend fun removeAt(id: String, at: Map<Int, String>): Boolean = withContext(Dispatchers.IO) {
        val file = fileOf(id) ?: return@withContext false
        val ids = readIds(file)
        // See [MusicServer.removeFromPlaylistAt]: each position has to hold the song expected.
        if (at.any { (index, trackId) -> ids.getOrNull(index) != trackId }) return@withContext false
        writeIds(file, ids.filterIndexed { i, _ -> i !in at.keys })
    }

    /** Whether the edit reached the file — see [removeAt]. */
    override suspend fun reorder(id: String, orderedIds: List<String>): Boolean = withContext(Dispatchers.IO) {
        val file = fileOf(id) ?: return@withContext false
        if (!isReorderOf(orderedIds, readIds(file))) return@withContext false
        writeIds(file, orderedIds)
    }

    /**
     * Track ids from a playlist file. Comments and blank lines are skipped
     * (`#EXTM3U`, `#EXTINF`), and relative paths resolve against the file's
     * own folder, as other tools write them.
     *
     * Paths are canonicalised: `/sdcard` is a symlink to `/storage/emulated/0`
     * and ids are compared as text, so `/sdcard/...` entries would otherwise
     * match nothing in the library and quietly disappear.
     */
    private fun readIds(file: File): List<String> = runCatching {
        file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val entry = if (line.startsWith("/")) File(line) else File(file.parentFile, line)
                // canonicalPath throws if it can't resolve, and a missing file
                // still belongs in the list so the count is honest.
                val path = runCatching { entry.canonicalPath }
                    .getOrElse { entry.normalize().absolutePath }
                PhoneFiles.ID_PREFIX + path
            }
    }.getOrElse {
        Log.w(TAG, "couldn't read ${file.name}: ${it::class.simpleName}")
        emptyList()
    }

    /** Absolute paths, one per line, under a header that marks the encoding. */
    private fun writeIds(file: File, trackIds: List<String>): Boolean = runCatching {
        val body = trackIds.mapNotNull { PhoneFiles.pathOf(it) }
        file.writeText((listOf("#EXTM3U") + body).joinToString("\n", postfix = "\n"))
        true
    }.getOrElse {
        Log.w(TAG, "couldn't write ${file.name}: ${it::class.simpleName}")
        false
    }
}
