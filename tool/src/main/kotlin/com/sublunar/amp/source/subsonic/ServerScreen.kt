package com.sublunar.amp.source.subsonic

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.source.SignInOutcome
import com.sublunar.amp.source.firstAnsweringSpelling
import com.sublunar.amp.ui.screens.QrScanScreen
import com.sublunar.amp.ui.screens.adoptServer
import com.sublunar.amp.ui.screens.editText
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A Subsonic server's address and login, for adding one (Bandcamp included)
 * or editing one in place. Fields are tap-to-edit on the LP3 keyboard.
 *
 * The cache is cleared only when the address or username changes, where the
 * cached rows may belong to another server or account; a new password keeps
 * the same library.
 */
class ServerScreen(
    sealed: SealedLightActivity,
    /** Which source is being edited; null to add a new one. */
    private val sourceId: String?,
    /**
     * Adding Bandcamp: its address is always the same, so it is filled in, and
     * a line above the login fields says where its credentials come from: they
     * are made for Subsonic apps on Bandcamp's site, not the account's own
     * login, and long enough that the form offers to scan them. Otherwise it is
     * an ordinary Subsonic server.
     */
    private val bandcamp: Boolean = false,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    /*
     * Form state lives on the screen, not in remember{}: the activity composes
     * only the top of the back stack, so opening the text editor for a field
     * disposes this composition, and a value typed there came back to state
     * that no longer existed. The screen object stays on the back stack.
     */
    private var nickname by mutableStateOf(if (bandcamp) "Bandcamp" else "")
    // null means "unchanged": the stored value shows until one is typed.
    private var url by mutableStateOf<String?>(if (bandcamp) BANDCAMP_ADDRESS else null)
    private var user by mutableStateOf<String?>(null)
    // The stored password is never seeded into this, so it can never be
    // rendered — the editor shows text in the clear.
    private var newPass by mutableStateOf<String?>(null)
    private var status by mutableStateOf<String?>(null)
    // Blocks Save while a check runs, and for good once a source is added:
    // each Save while adding mints a new source id, so a second would add the
    // server twice.
    private var saving by mutableStateOf(false)

    /** Set once this page has been popped, so an answer arriving late can't act on another. */
    private var gone = false

    override fun onScreenDestroy() {
        gone = true
    }

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val source = sources.firstOrNull { it.id == sourceId }
        val config = source?.toSubsonicConfig()

        val address = url ?: config?.baseUrl.orEmpty()
        val name = user ?: config?.username.orEmpty()
        val storedLength = config?.password?.length ?: 0
        val passwordSet = newPass?.isNotEmpty() ?: (storedLength > 0)
        val dots = "•".repeat((newPass?.length ?: storedLength).coerceIn(0, 24))

        ListScreen(
            onBack = { goBack() },
            title = source?.name ?: if (bandcamp) "Add Bandcamp" else "Add Source",
        ) {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                item { SectionLabel(status ?: "Tap a field to change it") }
                if (source == null) {
                    // Required, and first: with several sources the list is read
                    // by name, and "navidrome.example.com" twice tells you
                    // nothing about which is which.
                    item {
                        TextRow(
                            title = "Name",
                            value = nickname.ifBlank { "Required" },
                            onClick = { edit("Name", nickname) { nickname = it } },
                        )
                    }
                } else {
                    item {
                        TextRow(
                            title = "Name",
                            value = source.name,
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
                        value = address.ifBlank { "Not set" },
                        onClick = { edit("Address", address) { url = it } },
                    )
                }
                // Right above the fields it is about: the Bandcamp login is the
                // wrong answer here, and the place to say so is where someone is
                // about to type it — not at the top, where a status line replaces
                // it the moment Save is tapped.
                if (bandcamp) item { SectionLabel(BANDCAMP_HINT) }
                item {
                    TextRow(
                        title = "Username",
                        value = name.ifBlank { "Not set" },
                        onClick = { edit("Username", name) { user = it } },
                    )
                }
                item {
                    TextRow(
                        title = "Password",
                        value = when {
                            !passwordSet -> "Not set"
                            newPass != null -> "$dots · changed"
                            else -> dots
                        },
                        // Light's model: a password is seen while it is typed and
                        // never again, so the keyboard always opens empty.
                        // Submitting nothing leaves the stored password alone.
                        onClick = {
                            // "New" only where there is an old one to replace;
                            // adding a server, it is simply the password.
                            val title = if (storedLength > 0) "New password" else "Password"
                            edit(title, "") { if (it.isNotEmpty()) newPass = it }
                        },
                    )
                }
                // Bandcamp's long generated credentials are typed on a computer
                // instead: any QR generator, the username on the first line and
                // the password on the second, and one scan fills both. Only for
                // Bandcamp: any other server's login is the one you know.
                if (bandcamp || (source != null && isBandcampAddress(source.baseUrl))) item {
                    TextRow(
                        title = "Scan Username & Password",
                        subtitle = "On a computer, paste the username and password into any QR code " +
                            "generator, each on its own line, then scan the code",
                        subtitleLines = Int.MAX_VALUE,
                        onClick = {
                            if (saving) return@TextRow
                            navigateTo<String?>(
                                { QrScanScreen(it, title = "Scan Login") },
                                resultCallback = { text -> if (text != null) fillFromScan(text) },
                            )
                        },
                    )
                }
                // Only how the server is reached, nothing about what it plays:
                // quality lives on Data and Offline, and choosing a library is
                // the Sources page under More — this form's job ends at the
                // connection working.
                item { SectionLabel("Connection") }
                item {
                    TextRow(
                        title = if (saving) "Checking…" else "Save",
                        onClick = {
                            if (saving) return@TextRow
                            // Editing a source not read yet: nothing to save into.
                            if (sourceId != null && source == null) return@TextRow
                            if (source == null && nickname.isBlank()) {
                                status = "Give this source a name."
                                return@TextRow
                            }
                            if (address.isBlank() || name.isBlank()) {
                                status = "Enter an address and username."
                                return@TextRow
                            }
                            // Unchanged means keep whatever is stored.
                            save(source, address.trim(), name.trim(), newPass ?: config?.password.orEmpty())
                        },
                    )
                }
            }
        }
    }

    /** Fills the username and password from a scanned code — see [ScannedLogin]. */
    private fun fillFromScan(text: String) {
        val login = ScannedLogin.parse(text)
        if (login == null) {
            status = "That code didn't hold a username and a password on two lines."
            return
        }
        user = login.username
        newPass = login.password
        status = "Username and password filled in. Tap Save."
    }

    private fun edit(title: String, initial: String, onResult: (String) -> Unit) {
        // Not while a check is in flight: its answer goes back from this page,
        // and with the keyboard over it, it would close the keyboard instead.
        if (saving) return
        editText(title, initial, onResult)
    }

    /**
     * Ping each spelling of the address before committing, so a typo can't
     * leave the app pointed at something unreachable with no way back; then add
     * the source, or save the edit into [editing].
     */
    private fun save(editing: MusicSource?, address: String, username: String, password: String) {
        val nickname = nickname.trim()
        saving = true
        status = "Checking…"
        App.scope.launch {
            val outcome = firstAnsweringSpelling(SubsonicConfig.withoutRest(address)) { uri ->
                inspectSubsonicServer(uri, username, password, nickname)
            }
            val said = when (outcome) {
                is SignInOutcome.Settled -> outcome.reason
                is SignInOutcome.Missed -> outcome.reason
                // Leaving the form is also abandoning it.
                is SignInOutcome.Found -> when {
                    gone -> return@launch
                    editing != null -> saveEdit(editing, outcome.server.source)
                    else -> {
                        // Back on Main, and only if this page is still up:
                        // adding navigates, which after the user has left would
                        // act on whatever is on top. Its libraries are picked
                        // after this, before its first sync — see finishAddingSource.
                        withContext(Dispatchers.Main.immediate) {
                            if (!gone) adoptServer(outcome.server) { gone }
                        }
                        return@launch
                    }
                }
            }
            status = said
            saving = false
        }
    }

    /** [editing] takes the address and login [checked] answered at; what it says on the form. */
    private suspend fun saveEdit(editing: MusicSource, checked: MusicSource): String {
        // A different address or account may mean a different library, so the
        // cached rows can't be trusted; the same server with a new password
        // can. The cache is the active source's: another source's is left alone.
        val movedServer = editing.baseUrl != checked.baseUrl || editing.username != checked.username
        val reload = movedServer && App.source.value.id == editing.id
        if (reload) {
            App.playback.stop()
            App.library.clearCache()
        }
        App.settings.saveSource(
            editing.copy(baseUrl = checked.baseUrl, username = checked.username, password = checked.password),
        )
        return if (reload) "Saved — reloading library" else "Saved"
    }
}

/** Bandcamp's Subsonic server — the same for every account. */
private const val BANDCAMP_ADDRESS = "https://bandcamp.com/api/subsonic"

/** Whether a source's stored [address] is Bandcamp's, however it was typed when it was added. */
internal fun isBandcampAddress(address: String): Boolean =
    address.trim().trimEnd('/').equals(BANDCAMP_ADDRESS, ignoreCase = true)

/** Where Bandcamp's Subsonic credentials come from, shown just above the username and password. */
private const val BANDCAMP_HINT =
    "The login below is different from your usual Bandcamp username and password. To get " +
        "it, go to bandcamp.com, open Fan Settings, scroll down to Subsonic and generate one."
