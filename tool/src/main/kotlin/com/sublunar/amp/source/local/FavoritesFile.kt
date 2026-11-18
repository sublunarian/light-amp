package com.sublunar.amp.source.local

import com.sublunar.amp.data.Album
import com.sublunar.amp.data.PhoneFiles
import com.sublunar.amp.data.Track
import com.sublunar.amp.log.Log
import com.sublunar.amp.log.logName
import com.sublunar.amp.source.PhoneFavorites
import com.sublunar.amp.source.PhoneLikes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What is liked in the phone's own music, kept in `_AmpFavorites.m3u8` beside
 * the playlists so it can be copied off the phone with the music. A rescan
 * rebuilds the library's rows, and a reinstall loses them: this file is the
 * copy that lasts.
 *
 * Liked songs are ordinary entries, with paths relative to the file, so any
 * player can open it once the `Music/Amp` folder is copied whole. Artists and
 * albums have no standard form in a playlist, so they are comment lines other
 * players skip:
 *
 * ```
 * #EXTM3U
 * #PLAYLIST:Amp Favorites
 * #AMP-ARTIST:Nina Simone
 * #AMP-ALBUM:Nina Simone|Pastel Blues
 * #EXTINF:618,Nina Simone - Sinnerman
 * ../Nina Simone/Pastel Blues/09 Sinnerman.flac
 * ```
 *
 * A song whose file has gone stays listed, and is liked again when it returns.
 *
 * Scoped storage leaves a file Amp didn't write here read-only to Amp: one
 * brought back from a computer, or left by an earlier install. Amp can't change
 * or remove that file, so it saves a numbered copy, and the newest copy is the
 * one that counts.
 *
 * [moveInto] is how a finished save takes the file's place.
 */
internal class FavoritesFile(
    private val folder: File,
    private val moveInto: (File, File) -> Boolean = File::renameTo,
) : PhoneFavorites {

    private val lock = Mutex()

    override suspend fun <T> whileHeld(block: suspend (PhoneLikes?) -> T): T = lock.withLock {
        block(withContext(Dispatchers.IO) { load()?.likes() })
    }

    override suspend fun setSong(track: Track, liked: Boolean): Boolean {
        if (!PhoneFiles.isPhoneFile(track.id)) return false
        return change { saved ->
            saved.copy(songs = saved.songs.withLike(liked, { it.trackId == track.id }) { FavoriteSong(track.id, infoOf(track)) })
        }
    }

    override suspend fun setAlbum(album: Album, liked: Boolean): Boolean = change { saved ->
        saved.copy(albums = saved.albums.withLike(liked, { it.id == album.id }) { FavoriteAlbum(album.artist, album.title) })
    }

    override suspend fun setArtist(name: String, liked: Boolean): Boolean = change { saved ->
        saved.copy(artists = saved.artists.withLike(liked, { it == name }) { name })
    }

    /** Whether the change reached the file. One that can't be read is never written over. */
    private suspend fun change(edit: (FavoritesContent) -> FavoritesContent): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val saved = load() ?: return@withLock false
            val edited = edit(saved)
            edited == saved || save(renderFavorites(edited, folder))
        }
    }

    /** What the newest copy holds, nothing when there is none yet, null when it can't be read. */
    private fun load(): FavoritesContent? {
        val file = newest() ?: return FavoritesContent()
        return runCatching { parseFavorites(file.readText(), folder) }
            .onFailure { Log.w(TAG, "couldn't read the favorites file: ${it.logName()}") }
            .getOrNull()
    }

    private fun newest(): File? =
        folder.listFiles()?.filter { it.isFile && COPY.matches(it.name) }
            ?.maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { copyNumber(it.name) })

    private fun save(text: String): Boolean {
        if (!folder.isDirectory && !folder.mkdirs()) {
            Log.w(TAG, "couldn't create the playlists folder")
            return false
        }
        val current = newest()
        if (current != null) {
            if (replace(current, text)) return true
            Log.i(TAG, "the favorites file is read-only to Amp: saving a new copy")
        }
        return replace(unusedCopy(), text)
    }

    /**
     * Written whole beside [target] and then moved over it, so a save cut short
     * leaves the last one intact. Writing in place is for storage that refuses
     * the move, and fails untouched on a file Amp may not change.
     */
    private fun replace(target: File, text: String): Boolean {
        val saving = File(folder, SAVING_NAME)
        if (runCatching { saving.writeText(text) }.isSuccess && moveInto(saving, target)) return true
        runCatching { saving.delete() }
        return runCatching { target.writeText(text) }
            .onFailure { Log.w(TAG, "couldn't write the favorites file: ${it.logName()}") }
            .isSuccess
    }

    private fun unusedCopy(): File =
        generateSequence(1) { it + 1 }.map { File(folder, copyName(it)) }.first { !it.exists() }

    companion object {
        private const val TAG = "AmpLocal"
        private const val NAME = "_AmpFavorites"
        private const val EXTENSION = ".m3u8"
        private const val SAVING_NAME = "$NAME.saving$EXTENSION"
        private val COPY = Regex("""_AmpFavorites(?: (\d+))?\.m3u8""", RegexOption.IGNORE_CASE)

        /** Whether a file in the playlists folder is this one rather than a playlist, a save in progress included. */
        fun isFavoritesFile(fileName: String): Boolean = fileName.startsWith(NAME, ignoreCase = true)

        private fun copyName(number: Int): String = if (number == 1) NAME + EXTENSION else "$NAME $number$EXTENSION"

        private fun copyNumber(fileName: String): Int =
            COPY.matchEntire(fileName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        private fun infoOf(track: Track): String =
            "${track.durationMs / 1000},${track.artist} - ${track.title}".replace(LINE_BREAKS, " ")

        private val LINE_BREAKS = Regex("""[\r\n]+""")
    }
}

internal data class FavoriteSong(val trackId: String, val info: String?)

internal data class FavoriteAlbum(val artist: String, val title: String) {
    val id: String get() = LocalLibrary.albumId(artist, title)
}

internal data class FavoritesContent(
    val artists: List<String> = emptyList(),
    val albums: List<FavoriteAlbum> = emptyList(),
    val songs: List<FavoriteSong> = emptyList(),
) {
    fun likes() = PhoneLikes(
        songIds = songs.mapTo(HashSet()) { it.trackId },
        albumIds = albums.mapTo(HashSet()) { it.id },
        artistNames = artists.toSet(),
    )
}

/** Liked again it keeps its place, so the file stays in the order things were liked. */
private fun <T> List<T>.withLike(liked: Boolean, same: (T) -> Boolean, entry: () -> T): List<T> = when {
    !liked -> filterNot(same)
    any(same) -> this
    else -> this + entry()
}

private const val ARTIST_LINE = "#AMP-ARTIST:"
private const val ALBUM_LINE = "#AMP-ALBUM:"
private const val INFO_LINE = "#EXTINF:"

internal fun renderFavorites(content: FavoritesContent, folder: File): String = buildList {
    add("#EXTM3U")
    add("#PLAYLIST:Amp Favorites")
    content.artists.forEach { add(ARTIST_LINE + escaped(it)) }
    content.albums.forEach { add(ALBUM_LINE + escaped(it.artist) + "|" + escaped(it.title)) }
    content.songs.forEach { song ->
        song.info?.let { add(INFO_LINE + it) }
        add(entryFor(song.trackId, folder))
    }
}.joinToString("\n", postfix = "\n")

internal fun parseFavorites(text: String, folder: File): FavoritesContent {
    val artists = LinkedHashSet<String>()
    val albums = LinkedHashMap<String, FavoriteAlbum>()
    val songs = LinkedHashMap<String, FavoriteSong>()
    var info: String? = null
    // Editors on a computer may start the file with a byte-order mark.
    text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.forEach { line ->
        when {
            line.startsWith(ARTIST_LINE) ->
                fieldsOf(line.removePrefix(ARTIST_LINE)).joinToString("|").takeIf { it.isNotBlank() }?.let(artists::add)
            line.startsWith(ALBUM_LINE) -> {
                val fields = fieldsOf(line.removePrefix(ALBUM_LINE))
                if (fields.size == 2) FavoriteAlbum(fields[0], fields[1]).let { albums.putIfAbsent(it.id, it) }
            }
            line.startsWith(INFO_LINE) -> info = line.removePrefix(INFO_LINE)
            line.isEmpty() || line.startsWith("#") -> Unit
            else -> {
                val id = LocalPlaylists.trackIdOfEntry(line, folder)
                songs.putIfAbsent(id, FavoriteSong(id, info))
                info = null
            }
        }
    }
    return FavoritesContent(artists.toList(), albums.values.toList(), songs.values.toList())
}

/** The song's path from the file's own folder. `./` keeps a name starting with `#` from reading as a comment. */
private fun entryFor(trackId: String, folder: File): String {
    val relative = File(PhoneFiles.pathOf(trackId).orEmpty()).relativeToOrSelf(folder).path
    return if (relative.startsWith("#")) "./$relative" else relative
}

private fun escaped(field: String): String =
    field.replace("\\", "\\\\").replace("|", "\\|").replace("\n", "\\n").replace("\r", "\\r")

/** A line's fields, split on the bars [escaped] left bare. */
private fun fieldsOf(line: String): List<String> {
    val fields = mutableListOf(StringBuilder())
    var escaping = false
    for (c in line) {
        when {
            escaping -> {
                fields.last().append(
                    when (c) {
                        'n' -> '\n'
                        'r' -> '\r'
                        else -> c
                    },
                )
                escaping = false
            }
            c == '\\' -> escaping = true
            c == '|' -> fields += StringBuilder()
            else -> fields.last().append(c)
        }
    }
    return fields.map { it.toString() }
}
