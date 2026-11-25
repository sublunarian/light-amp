package com.sublunar.amp.source.local

import com.sublunar.amp.data.PhoneFiles
import com.sublunar.amp.data.ToolManagerFolders
import com.sublunar.amp.data.Playlist
import com.sublunar.amp.data.isReorderOf
import com.sublunar.amp.log.Log
import com.sublunar.amp.log.logName
import com.sublunar.amp.source.PhonePlaylists
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    private const val TAG = "AmpLocal"

    /** Files that couldn't be read, so one is logged once rather than at every listing. Never logged themselves. */
    private val unreadable = ConcurrentHashMap.newKeySet<String>()

    internal fun folder(): File {
        val dir = File(LocalLibrary.MUSIC_DIR, "Playlists")
        if (!broughtBack) bringBackOnce(dir)
        return dir
    }

    @Volatile
    private var broughtBack = false

    /**
     * Temporary, until the Tool Manager is finished: builds that never shipped
     * moved playlists and likes into Amp's own storage. They come back to
     * [dir] here, and the mark that they were moved goes, so the Tool Manager's
     * return moves them again. Remove with that return.
     */
    @Synchronized
    private fun bringBackOnce(dir: File) {
        if (broughtBack) return
        val from = ToolManagerFolders.ownFile("shared/Playlists") ?: return
        bringBack(from, dir)
        ToolManagerFolders.ownFile("playlists-moved")?.let { runCatching { it.delete() } }
        broughtBack = true
    }

    /**
     * Moves each playlist and likes file from [from] into [to], leaving any name
     * [to] already has. Playlists hold full paths and are copied as they are;
     * likes name songs relative to their folder, so they are rewritten for [to].
     */
    internal fun bringBack(from: File, to: File) {
        val files = from.listFiles()?.filter { it.isFile && it.name.endsWith(EXTENSION, ignoreCase = true) }.orEmpty()
        if (files.isEmpty()) return
        if (!to.isDirectory && !to.mkdirs()) {
            Log.w(TAG, "couldn't create the playlists folder")
            return
        }
        var count = 0
        for (file in files) {
            val target = File(to, file.name)
            if (target.exists()) continue
            val moved = runCatching {
                val text = file.readText()
                target.writeText(if (FavoritesFile.isFavoritesFile(file.name)) renderFavorites(parseFavorites(text, from), to) else text)
            }.onFailure {
                Log.w(TAG, "couldn't bring a playlist file back: ${it.logName()}")
                runCatching { target.delete() }
            }.isSuccess
            if (moved) {
                count++
                runCatching { file.delete() }
            }
        }
        Log.i(TAG, "brought $count of ${files.size} playlist files back to Music/Amp/Playlists")
    }

    private fun idFor(file: File): String = ID_PREFIX + file.absolutePath

    /** Only a file [list] could have offered: what this returns gets written, renamed and deleted. */
    private fun fileOf(id: String): File? =
        if (id.startsWith(ID_PREFIX)) {
            File(id.removePrefix(ID_PREFIX)).takeIf { it.parentFile == folder() && isPlaylistFile(it.name) }
        } else {
            null
        }

    /**
     * A name safe to be a filename. The name *is* the filename, with no sidecar
     * recording the real one, so anything the filesystem would choke on goes.
     * So does the underscore of a name the favorites file keeps for itself: a
     * playlist called that would never be listed.
     */
    internal fun fileNameFor(name: String): String {
        val cleaned = name.trim().replace(UNSAFE_IN_NAME, "_").take(120)
        val free = if (FavoritesFile.isFavoritesFile(cleaned)) cleaned.trimStart('_') else cleaned
        return (free.ifBlank { "Playlist" }) + EXTENSION
    }

    /** A playlist's file: the favorites file sits beside them, but it isn't one. */
    private fun isPlaylistFile(fileName: String): Boolean =
        fileName.endsWith(EXTENSION, ignoreCase = true) && !FavoritesFile.isFavoritesFile(fileName)

    override suspend fun list(): List<Playlist> = withContext(Dispatchers.IO) {
        val files = folder().listFiles()?.filter { it.isFile && isPlaylistFile(it.name) }.orEmpty()
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
                Log.w(TAG, "couldn't create the playlists folder")
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
        file.renameTo(target).also { if (!it) Log.w(TAG, "couldn't rename a playlist file") }
    }

    /** Whether the playlist is gone, counting one another app or adb already removed. */
    override suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val file = fileOf(id) ?: return@withContext false
        if (!file.exists()) return@withContext true
        file.delete().also { if (!it) Log.w(TAG, "couldn't delete a playlist file") }
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
     */
    private fun readIds(file: File): List<String> = runCatching {
        file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line -> trackIdOfEntry(line, file.parentFile) }
    }.onSuccess {
        if (unreadable.remove(file.path)) Log.i(TAG, "a playlist file is readable again")
    }.getOrElse {
        if (unreadable.add(file.path)) Log.w(TAG, "couldn't read a playlist file: ${it.logName()}")
        emptyList()
    }

    /**
     * The track a playlist line in [folder] names.
     *
     * Paths are canonicalised: `/sdcard` is a symlink to `/storage/emulated/0`
     * and ids are compared as text, so `/sdcard/...` entries would otherwise
     * match nothing in the library and quietly disappear.
     */
    internal fun trackIdOfEntry(line: String, folder: File?): String {
        val entry = if (line.startsWith("/")) File(line) else File(folder, line)
        // canonicalPath throws if it can't resolve, and a missing file
        // still belongs in the list so the count is honest.
        val path = runCatching { entry.canonicalPath }
            .getOrElse { entry.normalize().absolutePath }
        return PhoneFiles.ID_PREFIX + path
    }

    /** Absolute paths, one per line, under a header that marks the encoding. */
    private fun writeIds(file: File, trackIds: List<String>): Boolean = runCatching {
        val body = trackIds.mapNotNull { PhoneFiles.pathOf(it) }
        file.writeText((listOf("#EXTM3U") + body).joinToString("\n", postfix = "\n"))
        true
    }.getOrElse {
        Log.w(TAG, "couldn't write a playlist file: ${it.logName()}")
        false
    }
}
