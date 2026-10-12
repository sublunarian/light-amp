package com.sublunar.amp.ui.screens

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
import com.sublunar.amp.data.SubsonicClient
import com.sublunar.amp.data.SubsonicConfig
import com.sublunar.amp.data.SUBSONIC_REQUEST_FAILED
import com.sublunar.amp.data.ScannedLogin
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
    /**
     * Adding Bandcamp: its address is always the same, so it is filled in, and
     * the line at the top says where its credentials come from — they are made
     * for Subsonic apps on Bandcamp's site, not the account's own login.
     * Nothing else about the source is special; it is a Subsonic server.
     */
    private val bandcamp: Boolean = false,
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
    private var nickname by mutableStateOf(if (bandcamp) "Bandcamp" else "")
    private var url by mutableStateOf<String?>(if (bandcamp) BANDCAMP_ADDRESS else null)
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
                }
                if (source != null) {
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
                // Long generated credentials — Bandcamp's are — are typed on a
                // computer instead: any QR generator, the username on the first
                // line and the password on the second, and one scan fills both.
                item {
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
                                    // The form has done its job; sitting on it
                                    // saying "Saved" leaves the user to work out
                                    // that they should press back. Go where they
                                    // were headed.
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
            // `ping` is not proof of a login. Bandcamp answers it "ok" whatever
            // is sent, so a mistyped password saved cleanly and only surfaced
            // later as a library that wouldn't load — and its credentials are
            // generated, forty characters of them, which is exactly the kind
            // there is a typo in. So the address is settled by the ping and the
            // account by a read that needs one.
            val folders = if (ok.isSuccess) runCatching { client.getMusicFolders() } else null
            val refused = folders?.exceptionOrNull()
            client.close()
            if (ok.isFailure) {
                val error = ok.exceptionOrNull()
                // A ping refused in Subsonic's own words — Navidrome's "Wrong
                // username or password" — has found the server: this spelling
                // is the one, and that is the answer. Trying http:// next could
                // only bury it under a connection error from an address that
                // was never right.
                val subsonic = generateSequence(error) { it.cause }.take(8)
                    .filterIsInstance<SubsonicException>().firstOrNull()
                if (subsonic != null && (subsonic.code != null || subsonic.envelope)) {
                    return SaveOutcome.Said(explain(error))
                }
                lastError = error
                continue
            }
            // The ping settled the address: this spelling answered, so it is
            // the one, and nothing below tries another — a hiccup here moving
            // on to http:// could save a source that a redirect only happened
            // to carry through. What the folders call came back with decides
            // the rest. Only an answer about the account refuses the save; a
            // hiccup is reported as one, so a right password is never blamed.
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
                id = createdId ?: newSourceId().also { createdId = it },
                kind = SourceKind.SUBSONIC,
                name = nickname,
            )
            // The libraries the login check just read, kept for a source being
            // added: with two or more, the user picks which to show before the
            // first sync — see SaveOutcome.ChooseLibraries.
            val libraries = folders?.getOrNull().orEmpty().map { SourceLibrary(it.id, it.name) }
            App.settings.saveSource(
                existing.copy(
                    baseUrl = candidate,
                    username = username,
                    password = password,
                    libraries = if (source == null && libraries.isNotEmpty()) libraries else existing.libraries,
                ),
            )
            // A source added from here becomes the one being browsed — adding it
            // is asking for it — once its libraries are chosen: see
            // finishAddingSource.
            if (source == null) return SaveOutcome.Added(existing.id, libraries.size)
            return SaveOutcome.Said(if (movedServer) "Saved — reloading library" else "Saved")
        }
        return SaveOutcome.Said(explain(lastError))
    }
}

/** Bandcamp's Subsonic server — the same for every account. */
private const val BANDCAMP_ADDRESS = "https://bandcamp.com/api/subsonic"

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
private enum class LoginVerdict { ACCEPTED, REFUSED, UNCHECKABLE, UNREACHED }

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
private fun loginVerdict(error: Throwable?): LoginVerdict {
    if (error == null) return LoginVerdict.ACCEPTED
    val server = generateSequence(error) { it.cause }.take(8)
        .filterIsInstance<SubsonicException>().firstOrNull()
        ?: return LoginVerdict.UNREACHED
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
private fun refusedLogin(error: Throwable): String {
    val server = generateSequence(error) { it.cause }.take(8)
        .filterIsInstance<SubsonicException>().firstOrNull()
    val said = server?.takeIf { it.code != null }?.message
        ?.takeIf { it.isNotBlank() && it != SUBSONIC_REQUEST_FAILED }
    return said ?: "That address answered, but not for this account. Check the username and password."
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
    // Checked before the pass-through below: the client wraps an answer that
    // isn't JSON in a SubsonicException ("Unexpected response from server."),
    // and that sentence hides the real problem — a web page where the server
    // should be — which is the one with a fix the user can make. Only an
    // answer that wasn't JSON at all: JSON in an unexpected shape is the
    // server talking, and calling it a web page would send the user to fix an
    // address that is fine.
    // A page with an error status is different: a gateway saying the server
    // behind it is down (502, 503, 504). "Check the address" would send the
    // user to fix something that is fine, so that one keeps its HTTP message.
    val page = chain.filterIsInstance<SubsonicException>()
        .any { it.notJson && (it.status == null || it.status == 404) } ||
        (chain.none { it is SubsonicException } && chain.any { it is SerializationException })
    if (page) {
        return "That address answered with a web page, not a music server. Check the " +
            "address. If the server uses plain HTTP, type http:// in front of it."
    }
    // Already a sentence meant for a person — "Wrong username or password",
    // "Server returned HTTP 404." — so it is passed through as it stands.
    chain.filterIsInstance<SubsonicException>().firstOrNull()?.let {
        return it.message ?: "Couldn't connect."
    }
    return unreachedReason(error)
}
