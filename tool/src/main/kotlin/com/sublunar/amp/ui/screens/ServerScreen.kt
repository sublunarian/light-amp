package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.data.MusicFolder
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.newSourceId
import com.sublunar.amp.data.SubsonicClient
import com.sublunar.amp.data.SubsonicConfig
import com.sublunar.amp.data.SubsonicException
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Edit the server connection in place.
 *
 * Previously the only way to change any of this was Log Out, which threw away the
 * cached library as well — a heavy price for fixing a typo in a URL or rotating a
 * password. Fields are tap-to-edit on the LP3 keyboard, same as the login form.
 *
 * The cache is only cleared when the address or username changes, since that is
 * the case where the cached rows may belong to a different server or account; a
 * password change leaves the same library in place.
 */
class ServerScreen(
    sealed: SealedLightActivity,
    /** Which source is being edited; the active one when not given. */
    private val sourceId: String? = null,
    /** Start blank and create a new source on save, rather than editing one. */
    private val adding: Boolean = false,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    /*
     * Form state belongs to the screen, not to the composition.
     *
     * The activity composes only the top of the back stack, so opening the text
     * editor for a field tears this screen's composition down — and with it
     * every remember{} in Content(). Held there, each value typed was written
     * back into state that no longer existed: the Name field stayed empty no
     * matter what you entered, and an edited Address silently reverted to the
     * stored one when it was re-seeded from config.
     *
     * The screen object itself stays on the back stack, so these survive. Same
     * pattern as PlaylistsScreen.
     */
    private var nickname by mutableStateOf("")
    private var url by mutableStateOf<String?>(null)
    private var user by mutableStateOf<String?>(null)
    // null means "unchanged". The stored password is never seeded into this, so
    // it can never be rendered — the editor shows text in the clear.
    private var newPass by mutableStateOf<String?>(null)
    private var status by mutableStateOf<String?>(null)
    private var saving by mutableStateOf(false)

    /**
     * The id minted the first time Save succeeded here, so pressing it again
     * updates that source instead of creating another.
     *
     * Adding is the only path that can duplicate: `source` stays null for the
     * life of this screen, so without this every successful Save called
     * [newSourceId] afresh and `saveSource` — an upsert keyed on id — appended
     * rather than replaced. One user got five copies of the same server that
     * way, because a successful save used to leave them sitting on this form
     * with nothing to suggest it had worked.
     */
    private var createdId: String? = null

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val active by App.settings.activeSource.collectAsState(initial = null)
        val source = if (adding) null else sources.firstOrNull { it.id == sourceId } ?: active
        val config = source?.toConfig()

        // Seed from the stored config once it arrives, without clobbering edits.
        LaunchedEffect(config) {
            val current = config ?: return@LaunchedEffect
            if (url == null) url = current.baseUrl
            if (user == null) user = current.username
        }
                var folders by remember { mutableStateOf<List<MusicFolder>>(emptyList()) }
        LaunchedEffect(source?.id) {
            folders = if (source?.supportsLibraries == true) App.library.musicFolders() else emptyList()
        }
        val libraryName = folders.firstOrNull { it.id == source?.libraryId }?.name ?: "All Libraries"

        val address = url.orEmpty()
        val name = user.orEmpty()
        val storedLength = config?.password?.length ?: 0
        val passwordSet = newPass?.isNotEmpty() ?: (storedLength > 0)
        val dots = "•".repeat((newPass?.length ?: storedLength).coerceIn(0, 24))

        ListScreen(onBack = { goBack() }, title = source?.name ?: "Add Source") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                item { SectionLabel(status ?: "Tap a field to change it") }
                if (source == null) {
                    // Required, and first: with several sources the list is read
                    // by name, and "navidrome.example.com" twice tells you
                    // nothing about which is which.
                    item {
                        TextRow(
                            title = "Name",
                            subtitle = nickname.ifBlank { "Required" },
                            onClick = { edit("Name", nickname) { nickname = it } },
                        )
                    }
                }
                if (source != null) {
                    item {
                    TextRow(
                        title = "Name",
                        subtitle = source.name,
                        onClick = {
                            edit("Name", source.name) { text ->
                                App.scope.launch {
                                    App.settings.saveSource(source.copy(name = text.trim()))
                                }
                            }
                        },
                    )
                    }
                }
                item {
                    TextRow(
                        title = "Address",
                        subtitle = address.ifBlank { "Not set" },
                        onClick = { edit("Address", address) { url = it } },
                    )
                }
                item {
                    TextRow(
                        title = "Username",
                        subtitle = name.ifBlank { "Not set" },
                        onClick = { edit("Username", name) { user = it } },
                    )
                }
                item {
                    TextRow(
                        title = "Password",
                        subtitle = when {
                            !passwordSet -> "Not set"
                            newPass != null -> "$dots · changed"
                            else -> dots
                        },
                        // Always opens empty and masks as you type. Submitting
                        // nothing leaves the stored password alone.
                        onClick = {
                            navigateTo<String?>(
                                { PasswordEntryScreen(it, title = "New password") },
                                resultCallback = { text -> if (text != null) newPass = text },
                            )
                        },
                    )
                }
                // Quality lives on the source's own page now, next to the
                // download setting it pairs with; this screen is about how the
                // server is reached. The library picker stays because it is only
                // meaningful for a server that is being browsed.
                if (source != null && source.id == active?.id) {
                    item { SectionLabel("Playback") }
                    item {
                        TextRow(
                            title = "Library",
                            subtitle = libraryName,
                            onClick = { go { LibraryFolderScreen(it) } },
                        )
                    }
                }
                item { SectionLabel("Connection") }
                item {
                    TextRow(
                        title = if (saving) "Checking…" else "Save",
                        onClick = {
                            if (saving) return@TextRow
                            if (source == null && nickname.isBlank()) {
                                status = "Give this source a name."
                                return@TextRow
                            }
                            val previous = config
                            saving = true
                            status = "Checking…"
                            App.scope.launch {
                                val outcome = verifyAndSave(
                                    address.trim(),
                                    name.trim(),
                                    // Unchanged means keep whatever is stored.
                                    newPass ?: previous?.password.orEmpty(),
                                    previous,
                                    source,
                                    nickname.trim(),
                                )
                                when (outcome) {
                                    // The form has done its job; sitting on it
                                    // saying "Saved" leaves the user to work out
                                    // that they should press back. Go where they
                                    // were headed.
                                    is SaveOutcome.Added -> withContext(Dispatchers.Main) {
                                        LibraryNav.selectTab(LibraryTab.ALBUMS)
                                        popToRoot()
                                    }
                                    is SaveOutcome.Said -> {
                                        status = outcome.text
                                        saving = false
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    private fun edit(title: String, initial: String, onResult: (String) -> Unit) {
        navigateTo<String?>(
            { TextEntryScreen(it, title = title, initial = initial) },
            resultCallback = { text -> if (text != null) onResult(text) },
        )
    }

    /**
     * Ping each candidate spelling of the address before committing, so a typo
     * can't leave the app pointed at something unreachable with no way back.
     */
    private suspend fun verifyAndSave(
        address: String,
        username: String,
        password: String,
        previous: SubsonicConfig?,
        source: MusicSource?,
        /** The name the user gave a source they are adding. */
        nickname: String,
    ): SaveOutcome {
        if (address.isBlank() || username.isBlank()) {
            return SaveOutcome.Said("Enter an address and username.")
        }
        var lastError: Throwable? = null
        for (candidate in SubsonicConfig.candidates(address)) {
            val config = SubsonicConfig(candidate, username, password)
            val client = SubsonicClient(config)
            val ok = runCatching { client.ping() }
            client.close()
            if (ok.isFailure) {
                lastError = ok.exceptionOrNull()
                continue
            }
            // A different address or account may mean a different library, so the
            // cached rows can't be trusted; the same server with a new password can.
            val movedServer = previous != null &&
                (previous.baseUrl != candidate || previous.username != username)
            if (movedServer) {
                App.playback.stop()
                App.library.clearCache()
            }
            val existing = source ?: MusicSource(
                id = createdId ?: newSourceId().also { createdId = it },
                kind = SourceKind.SUBSONIC,
                name = nickname,
            )
            App.settings.saveSource(
                existing.copy(
                    baseUrl = candidate,
                    username = username,
                    password = password,
                ),
            )
            // A source added from here becomes the one being browsed: adding it
            // is asking for it.
            if (source == null) {
                App.settings.setActiveSource(existing.id)
                return SaveOutcome.Added
            }
            return SaveOutcome.Said(if (movedServer) "Saved — reloading library" else "Saved")
        }
        return SaveOutcome.Said(explain(lastError))
    }
}

/** What came of pressing Save. */
private sealed interface SaveOutcome {
    /** Shown on the form; the user stays where they are. */
    data class Said(val text: String) : SaveOutcome

    /** A source was added and made active — the form has nothing left to say. */
    data object Added : SaveOutcome
}

/**
 * What went wrong, in words that point at a fix.
 *
 * The raw exceptions are unhelpful at best and alarming at worst. Pointing this
 * at an address that answers with a web page produced, verbatim: "Unexpected
 * JSON token at offset 0: Expected start of the object '{', but had '<' instead
 * at path: $ JSON input: <!DOCTYPE html>…". Every word of that is true and none
 * of it tells you the actual problem, which is usually a reverse proxy serving
 * its own landing page on HTTPS while the server sits behind plain HTTP.
 *
 * Causes are walked because Ktor wraps: the interesting exception is rarely the
 * outermost one.
 */
private fun explain(error: Throwable?): String {
    if (error == null) return "Couldn't connect."
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    // Already a sentence meant for a person — "Wrong username or password",
    // "Server returned HTTP 404." — so it is passed through as it stands.
    chain.filterIsInstance<SubsonicException>().firstOrNull()?.let {
        return it.message ?: "Couldn't connect."
    }
    return when {
        chain.any { it is SerializationException } ->
            "That address answered with a web page, not a music server. Check the " +
                "address, and if the server is on plain HTTP enter it as http://…"
        chain.any { it is UnknownHostException } ->
            "Couldn't find that address. Check the spelling, and that this phone is " +
                "on the same network."
        chain.any { it is SSLException } ->
            "The HTTPS certificate wasn't accepted. A self-signed certificate needs " +
                "http:// instead."
        chain.any { it is ConnectException || it is SocketTimeoutException } ->
            "Nothing answered at that address. Check the server is running and the " +
                "port is right."
        else -> error.message ?: "Couldn't connect."
    }
}
