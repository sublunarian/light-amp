package com.sublunar.amp.data

import com.thelightphone.toolmanager.ClientLeafNode
import com.thelightphone.toolmanager.ClientToolManifest
import com.thelightphone.toolmanager.FileBrowserSpec
import com.thelightphone.toolmanager.LightFileProvider
import java.io.File

/**
 * The folders a computer fills through LightOS's Tool Manager.
 *
 * They are Amp's own storage, under the `shared` folder the SDK's file
 * provider serves: the Tool Manager can write nowhere else. The phone's media
 * index never looks there, so covers uploaded with the music stay out of the
 * photos, and every file in them is Amp's to edit or delete, whoever put it there.
 * They go when Amp is uninstalled.
 */
object ToolManagerFolders {

    private const val MUSIC = "Music"
    private const val PLAYLISTS = "Playlists"

    /** Set by [attach]: the SDK hands out the app's storage only to a booted tool. */
    @Volatile
    private var shared: File? = null

    /**
     * Called at boot, before anything reads the phone's music. Canonical, as
     * playlist entries are: track ids are compared as text.
     */
    fun attach(filesDir: File) {
        val root = File(filesDir, LightFileProvider.SHARED_DIR).canonicalFile
        listOf(MUSIC, PLAYLISTS).forEach { runCatching { File(root, it).mkdirs() } }
        shared = root
    }

    val musicDir: File? get() = shared?.let { File(it, MUSIC) }

    val playlistsDir: File? get() = shared?.let { File(it, PLAYLISTS) }

    /** Outside [shared], so the Tool Manager never lists it: playlists from `Music/Amp` were moved once. */
    val playlistsMovedMarker: File? get() = shared?.parentFile?.let { File(it, "playlists-moved") }

    /**
     * Whether anything in these folders came, went or changed after [ms].
     * Folders are asked as well as files: a folder's date moves when
     * something in it is added or removed, even a file that keeps the date
     * it had on the computer.
     */
    fun changedSince(ms: Long): Boolean =
        listOfNotNull(musicDir, playlistsDir).any { changedSince(it, ms, depth = 0) }

    private fun changedSince(dir: File, ms: Long, depth: Int): Boolean {
        if (dir.lastModified() > ms) return true
        if (depth > MAX_DEPTH) return false
        return dir.listFiles().orEmpty().any { entry ->
            if (entry.isDirectory) changedSince(entry, ms, depth + 1) else entry.lastModified() > ms
        }
    }

    /** As deep as the library walks. */
    private const val MAX_DEPTH = 6

    /** What the Tool Manager shows for Amp. Music is left out of backups: it is the size of a library. */
    fun manifest(): ClientToolManifest = ClientToolManifest(
        title = "Amp",
        roots = listOf(
            ClientLeafNode(
                spec = FileBrowserSpec(
                    label = "Music",
                    path = MUSIC,
                    headerText = "Add albums here, in folders. Amp reads the songs' tags, so folder names don't matter.",
                ),
                canBeBackedUp = false,
            ),
            ClientLeafNode(
                spec = FileBrowserSpec(
                    label = "Playlists",
                    path = PLAYLISTS,
                    headerText = "M3U8 playlists. Amp keeps its own playlists and likes here too.",
                ),
                canBeBackedUp = true,
            ),
        ),
    )
}
