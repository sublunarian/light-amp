package com.sublunar.amp.data

import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Playlists for the phone's own music, kept as `.m3u8` files on the phone.
 *
 * A local source has no server to hold them, so they live beside the music in
 * `Music/Amp/Playlists` in the format every other music player already reads.
 * That is the point of choosing M3U over something of our own: a playlist made
 * here opens in VLC or foobar or Navidrome's importer, and one made anywhere
 * else shows up here. Dropping a `.m3u8` into the folder over adb is a
 * supported way to make a playlist.
 *
 * Entries are absolute paths, which is also exactly what a track id is once
 * [LocalLibrary.ID_PREFIX] is taken off — so the mapping in both directions is
 * a string operation and never needs the library to be loaded.
 *
 * Everything here touches the disk, so it all runs on [Dispatchers.IO].
 */
object LocalPlaylists {

    /** Marks a playlist id as a file on this phone. */
    private const val ID_PREFIX = "m3u:"

    /** What a playlist file is called. `.m3u8` rather than `.m3u` says UTF-8. */
    private const val EXTENSION = ".m3u8"

    private const val TAG = "LocalPlaylists"

    private fun folder(): File = File("/storage/emulated/0/${LocalLibrary.FOLDER}", "Playlists")

    fun isLocalPlaylist(id: String): Boolean = id.startsWith(ID_PREFIX)

    private fun idFor(file: File): String = ID_PREFIX + file.absolutePath

    private fun fileOf(id: String): File? =
        if (id.startsWith(ID_PREFIX)) File(id.removePrefix(ID_PREFIX)) else null

    /**
     * A name safe to be a filename.
     *
     * The name *is* the filename — there is no sidecar recording the real one —
     * so anything the filesystem would choke on has to go. A name that reduces
     * to nothing still needs a file, hence the fallback.
     */
    private fun fileNameFor(name: String): String {
        val cleaned = name.trim().replace(Regex("""[/\\:*?"<>|]"""), "_").take(120)
        return (cleaned.ifBlank { "Playlist" }) + EXTENSION
    }

    suspend fun list(): List<Playlist> = withContext(Dispatchers.IO) {
        val files = folder().listFiles()?.filter {
            it.isFile && it.name.endsWith(EXTENSION, ignoreCase = true)
        }.orEmpty()
        files.sortedBy { it.name.lowercase() }.map { file ->
            val ids = readIds(file)
            Playlist(
                id = idFor(file),
                name = file.name.removeSuffix(EXTENSION),
                // The first track's own file, so the row gets the same embedded
                // cover the track would show. See ArtworkLoader.
                coverArtId = ids.firstOrNull(),
                createdAt = file.lastModified(),
                updatedAt = file.lastModified(),
                trackIds = ids,
            )
        }
    }

    suspend fun trackIds(id: String): List<String> = withContext(Dispatchers.IO) {
        readIds(fileOf(id) ?: return@withContext emptyList())
    }

    /** Returns the new playlist's id, or null if it couldn't be written. */
    suspend fun create(name: String, trackIds: List<String>): String? =
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

    suspend fun rename(id: String, name: String) = withContext(Dispatchers.IO) {
        val file = fileOf(id) ?: return@withContext
        if (!file.isFile) return@withContext
        val target = File(file.parentFile, fileNameFor(name))
        if (target.absolutePath == file.absolutePath || target.exists()) return@withContext
        if (!file.renameTo(target)) Log.w(TAG, "couldn't rename ${file.name}")
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val file = fileOf(id) ?: return@withContext
        if (file.isFile && !file.delete()) Log.w(TAG, "couldn't delete ${file.name}")
    }

    suspend fun add(id: String, trackId: String) = withContext(Dispatchers.IO) {
        val file = fileOf(id) ?: return@withContext
        // Appending duplicates is allowed on purpose: a playlist is an ordered
        // list, and the same song twice is a decision someone might mean.
        writeIds(file, readIds(file) + trackId)
        Unit
    }

    suspend fun removeAt(id: String, index: Int) = withContext(Dispatchers.IO) {
        val file = fileOf(id) ?: return@withContext
        val ids = readIds(file)
        if (index !in ids.indices) return@withContext
        writeIds(file, ids.filterIndexed { i, _ -> i != index })
        Unit
    }

    suspend fun reorder(id: String, orderedIds: List<String>) = withContext(Dispatchers.IO) {
        val file = fileOf(id) ?: return@withContext
        writeIds(file, orderedIds)
        Unit
    }

    /**
     * Track ids from a playlist file.
     *
     * Comments and blank lines are skipped, so an `#EXTM3U` header and the
     * `#EXTINF` lines other players write are read without complaint. Relative
     * paths resolve against the file's own folder, which is what a playlist
     * written by another tool will normally contain.
     *
     * Paths are canonicalised, which matters more than it looks: `/sdcard` is a
     * symlink to `/storage/emulated/0`, and an id is only ever compared as text.
     * A playlist written elsewhere with `/sdcard/...` entries would otherwise
     * produce ids that match nothing in the library, and every track in it would
     * quietly disappear rather than fail in any visible way.
     */
    private fun readIds(file: File): List<String> = runCatching {
        file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val entry = if (line.startsWith("/")) File(line) else File(file.parentFile, line)
                // canonicalPath touches the filesystem and throws if it can't
                // resolve; a missing file should still make it into the list so
                // the count is honest, so fall back to plain normalisation.
                val path = runCatching { entry.canonicalPath }
                    .getOrElse { entry.normalize().absolutePath }
                LocalLibrary.ID_PREFIX + path
            }
    }.getOrElse {
        Log.w(TAG, "couldn't read ${file.name}: ${it.message}")
        emptyList()
    }

    /** Absolute paths, one per line, under a header that marks the encoding. */
    private fun writeIds(file: File, trackIds: List<String>): Boolean = runCatching {
        val body = trackIds.mapNotNull { LocalLibrary.pathOf(it) }
        file.writeText((listOf("#EXTM3U") + body).joinToString("\n", postfix = "\n"))
        true
    }.getOrElse {
        Log.w(TAG, "couldn't write ${file.name}: ${it.message}")
        false
    }
}
