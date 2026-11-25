package com.sublunar.amp.data

import com.sublunar.amp.log.Log
import com.thelightphone.toolmanager.ClientLeafNode
import com.thelightphone.toolmanager.ClientToolManifest
import com.thelightphone.toolmanager.FileBrowserSpec
import com.thelightphone.toolmanager.FormItem
import com.thelightphone.toolmanager.FormSpec
import com.thelightphone.toolmanager.FormSubmission
import com.thelightphone.toolmanager.LightFileProvider
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

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

    /**
     * Where LightOS keeps the Tool Manager, as Amp tells people. Only for now:
     * Light is moving it, so check it on the current LightOS before every release.
     */
    const val LOCATION = "LightOS Settings > Developer > Tool Manager"

    private const val MUSIC = "Music"
    private const val PLAYLISTS = "Playlists"
    private const val BANDCAMP_LOGIN = "BandcampLogin"

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

    private val bandcampLoginDir: File? get() = shared?.let { File(it, BANDCAMP_LOGIN) }

    /**
     * A Bandcamp login sent from a computer, until the Bandcamp form takes it.
     * Kept in memory only: the file it came in is deleted as soon as it is read.
     */
    val sentLogin = MutableStateFlow<SentLogin?>(null)

    /**
     * Reads the newest login the Bandcamp Login form sent, and deletes every
     * form file there, read or not: a password is not left lying in a file.
     */
    fun takeSentLogin() {
        val dir = bandcampLoginDir ?: return
        val forms = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }.orEmpty()
        if (forms.isEmpty()) return
        val login = runCatching { readLogin(forms.maxBy { it.lastModified() }.readText()) }.getOrNull()
        forms.forEach { runCatching { it.delete() } }
        if (login == null) {
            Log.w(TAG, "a Bandcamp login form arrived without a username and a password")
            return
        }
        Log.i(TAG, "a Bandcamp login arrived from the Tool Manager")
        sentLogin.value = login
    }

    /** The username and password in a form's answers, or null without both. */
    internal fun readLogin(json: String): SentLogin? {
        val answers = FORM_JSON.decodeFromString(FormSubmission.serializer(), json)
            .responses.associate { it.item.id to it.response }
        val username = answers[USERNAME]?.trim().orEmpty()
        val password = answers[PASSWORD].orEmpty()
        return if (username.isEmpty() || password.isEmpty()) null else SentLogin(username, password)
    }

    private val FORM_JSON = Json { ignoreUnknownKeys = true }
    private const val USERNAME = "username"
    private const val PASSWORD = "password"
    private const val TAG = "AmpSync"

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
            // Never backed up: it holds a password until Amp reads it.
            ClientLeafNode(
                spec = FormSpec(
                    label = "Bandcamp Login",
                    path = BANDCAMP_LOGIN,
                    items = listOf(
                        FormItem(id = USERNAME, label = "Username", hint = "", hidden = false, required = true),
                        FormItem(id = PASSWORD, label = "Password", hint = "", hidden = true, required = true),
                    ),
                    headerText = "The Subsonic login Bandcamp made for you: on bandcamp.com, open Fan Settings " +
                        "and scroll down to Subsonic. Amp fills it into Add Bandcamp.",
                    buttonText = "Send to Amp",
                ),
                canBeBackedUp = false,
            ),
        ),
    )
}
