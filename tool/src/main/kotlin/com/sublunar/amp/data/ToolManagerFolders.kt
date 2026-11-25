package com.sublunar.amp.data

import com.sublunar.amp.log.Log
import com.thelightphone.toolmanager.ClientLeafNode
import com.thelightphone.toolmanager.ClientToolManifest
import com.thelightphone.toolmanager.FormItem
import com.thelightphone.toolmanager.FormSpec
import com.thelightphone.toolmanager.FormSubmission
import com.thelightphone.toolmanager.LightFileProvider
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

/**
 * What a computer sends through LightOS's Tool Manager: for now, the Bandcamp
 * Login form. Music and playlists join when the Tool Manager is finished.
 *
 * It lands in Amp's own storage, under the `shared` folder the SDK's file
 * provider serves: the Tool Manager can write nowhere else.
 */
object ToolManagerFolders {

    /**
     * Where LightOS keeps the Tool Manager, as Amp tells people. Only for now:
     * Light is moving it, so check it on the current LightOS before every release.
     */
    const val LOCATION = "LightOS Settings > Developer > Tool Manager"

    private const val BANDCAMP_LOGIN = "BandcampLogin"

    /** Set by [attach]: the SDK hands out the app's storage only to a booted tool. */
    @Volatile
    private var shared: File? = null

    /** Called at boot. */
    fun attach(filesDir: File) {
        shared = File(filesDir, LightFileProvider.SHARED_DIR).canonicalFile
    }

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


    /** What the Tool Manager shows for Amp. */
    fun manifest(): ClientToolManifest = ClientToolManifest(
        title = "Amp",
        roots = listOf(
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
