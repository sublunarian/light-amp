package com.sublunar.amp.source.subsonic

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.SourceLibrary
import com.sublunar.amp.data.newSourceId
import com.sublunar.amp.source.causes
import com.sublunar.amp.source.unreachedReason
import com.sublunar.amp.ui.screens.QrScanScreen
import com.sublunar.amp.ui.screens.TextEntryScreen
import com.sublunar.amp.ui.screens.finishAddingSource
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
    /** Which source is being edited; null when [adding]. */
    private val sourceId: String?,
    /** Start blank and create a new source on save, rather than editing one. */
    private val adding: Boolean = false,
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
    private var url by mutableStateOf<String?>(if (bandcamp) BANDCAMP_ADDRESS else null)
    private var user by mutableStateOf<String?>(null)
    // null means "unchanged". The stored password is never seeded into this, so
    // it can never be rendered — the editor shows text in the clear.
    private var newPass by mutableStateOf<String?>(null)
    private var status by mutableStateOf<String?>(null)
    // Blocks Save while a check runs, and for good once a source is added:
    // each Save while adding mints a new source id, so a second would add the
    // server twice.
    private var saving by mutableStateOf(false)

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val active by App.settings.activeSource.collectAsState(initial = null)
        val source = if (adding) null else sources.firstOrNull { it.id == sourceId } ?: active
        val config = source?.toSubsonicConfig()

        // Seed from the stored config once it arrives, without clobbering edits.
        LaunchedEffect(config) {
            val current = config ?: return@LaunchedEffect
            if (url == null) url = current.baseUrl
            if (user == null) user = current.username
        }

        val address = url.orEmpty()
        val name = user.orEmpty()
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
                                    // Done: go where the user was headed rather
                                    // than sit here saying "Saved".
                                    is SaveOutcome.Added -> withContext(Dispatchers.Main) {
                                        finishAddingSource(outcome.sourceId, outcome.libraryCount)
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
            // The ping settles the address and a read that needs a login settles
            // the account: Bandcamp answers a ping "ok" whatever login is sent.
            val folders = if (ok.isSuccess) runCatching { client.getMusicFolders() } else null
            val refused = folders?.exceptionOrNull()
            client.close()
            if (ok.isFailure) {
                val error = ok.exceptionOrNull()
                // A ping refused in Subsonic's own words has found the server,
                // and that is the answer: trying http:// next could only bury
                // it under a connection error.
                val subsonic = error?.subsonicAnswer()
                if (subsonic != null && (subsonic.code != null || subsonic.envelope)) {
                    return SaveOutcome.Said(explain(error))
                }
                lastError = error
                continue
            }
            // This spelling answered, so nothing below tries another. Only an
            // answer about the account refuses the save; a hiccup is reported
            // as one, so a right password is never blamed.
            when (loginVerdict(refused)) {
                LoginVerdict.ACCEPTED, LoginVerdict.UNCHECKABLE -> Unit
                LoginVerdict.REFUSED -> return SaveOutcome.Said(refusedLogin(refused!!))
                LoginVerdict.UNREACHED -> return SaveOutcome.Said(
                    "That address answered, then stopped answering before the account " +
                        "could be checked. Try Save again.",
                )
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
                id = newSourceId(),
                kind = SourceKind.SUBSONIC,
                name = nickname,
            )
            // The libraries the login check just read, kept for a source being
            // added: with two or more, the user picks which to show before the
            // first sync — see finishAddingSource.
            val libraries = folders?.getOrNull().orEmpty().map { SourceLibrary(it.id, it.name) }
            App.settings.saveSource(
                existing.copy(
                    baseUrl = candidate,
                    username = username,
                    password = password,
                    libraries = if (source == null && libraries.isNotEmpty()) libraries else existing.libraries,
                ),
            )
            if (source == null) return SaveOutcome.Added(existing.id, libraries.size)
            return SaveOutcome.Said(if (movedServer) "Saved — reloading library" else "Saved")
        }
        return SaveOutcome.Said(explain(lastError))
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

/** What came of pressing Save. */
private sealed interface SaveOutcome {
    /** Shown on the form; the user stays where they are. */
    data class Said(val text: String) : SaveOutcome

    /**
     * A source was added and saved, not yet active — the form has nothing
     * left to say; [finishAddingSource] offers its libraries if it has two or
     * more, then makes it active.
     */
    data class Added(val sourceId: String, val libraryCount: Int) : SaveOutcome
}

/** What the folders call said about the account, once the ping has settled the address. */
internal enum class LoginVerdict { ACCEPTED, REFUSED, UNCHECKABLE, UNREACHED }

/**
 * Whether a failed folders call is the account being refused or something
 * else getting in the way.
 *
 * Refused: a Subsonic error from the server itself (it carries a code), or
 * HTTP 401, 403 or 500 — a bare 500 being how Bandcamp says "not this login".
 * Uncheckable: the server has no such call, or answered in a shape this client
 * can't read (it answered, so the account got in). Everything else — a
 * timeout, a 502, a 500 that is a gateway's page rather than the server's own,
 * a web page where JSON should be — is the network or something in front of
 * the server, and says nothing about the password.
 */
internal fun loginVerdict(error: Throwable?): LoginVerdict {
    if (error == null) return LoginVerdict.ACCEPTED
    val server = error.subsonicAnswer() ?: return LoginVerdict.UNREACHED
    return when {
        server.endpointMissing -> LoginVerdict.UNCHECKABLE
        server.code != null -> LoginVerdict.REFUSED
        server.envelope -> LoginVerdict.UNCHECKABLE
        server.status == 401 || server.status == 403 -> LoginVerdict.REFUSED
        server.status == 500 && !server.notJson -> LoginVerdict.REFUSED
        else -> LoginVerdict.UNREACHED
    }
}

/**
 * The address answered and the account did not.
 *
 * A server that says why — Subsonic's own "Wrong username or password" — is
 * quoted. One that doesn't, and Bandcamp doesn't (a bare HTTP 500), gets a
 * sentence that claims only what is known: the address is fine and the login
 * is what was refused. Naming the password outright would be a guess.
 */
internal fun refusedLogin(error: Throwable): String {
    val said = error.subsonicAnswer()?.takeIf { it.code != null }?.message
        ?.takeIf { it.isNotBlank() && it != SUBSONIC_REQUEST_FAILED }
    return said ?: "That address answered, but not for this account. Check the username and password."
}

/**
 * What went wrong, in words that point at a fix. A raw parse error is true and
 * useless; the usual cause is a reverse proxy serving its own page on HTTPS
 * while the server sits behind plain HTTP.
 */
internal fun explain(error: Throwable?): String {
    if (error == null) return "Couldn't connect."
    val chain = error.causes().toList()
    // Before the pass-through below, whose "Unexpected response from server."
    // would hide the one problem the user can fix. Only an answer that wasn't
    // JSON at all: JSON in another shape is the server talking, at an address
    // that is fine. A gateway's error page (502, 503, 504) keeps its HTTP
    // message for the same reason.
    val page = chain.filterIsInstance<SubsonicException>()
        .any { it.notJson && (it.status == null || it.status == 404) } ||
        (chain.none { it is SubsonicException } && chain.any { it is SerializationException })
    if (page) {
        return "That address answered with a web page, not a music server. Check the " +
            "address. If the server uses plain HTTP, type http:// in front of it."
    }
    // Already a sentence meant for a person, such as "Wrong username or password".
    error.subsonicAnswer()?.let {
        return it.message ?: "Couldn't connect."
    }
    return unreachedReason(error)
}

/** The Subsonic answer inside this exception, however deep the HTTP client wrapped it. */
private fun Throwable.subsonicAnswer(): SubsonicException? =
    causes().filterIsInstance<SubsonicException>().firstOrNull()
