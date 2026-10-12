package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.sublunar.amp.App
import com.sublunar.amp.data.MusicFolder
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.PlexClient
import com.sublunar.amp.data.PlexException
import com.sublunar.amp.data.PlexAccount
import com.sublunar.amp.data.PlexResource
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.SourceLibrary
import com.sublunar.amp.data.newSourceId
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

/**
 * Linking a Plex account by code.
 *
 * The phone shows four characters; the user types them at plex.tv/link on any
 * other device. Nothing is typed on this keyboard, and no password reaches the
 * app. Once the code is claimed, the account's servers are listed and the user
 * picks one — so the server address isn't typed either.
 *
 * There is a way in for a server that was never claimed to an account: see
 * [PlexManualScreen], reached from the row at the bottom.
 */
class PlexLinkScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    /** A server is being looked at; its answer will go back from this page. */
    private var looking by mutableStateOf(false)

    /** Set once this page has been popped, so an answer arriving late can't act on another. */
    private var gone = false

    override fun onScreenDestroy() {
        gone = true
    }


    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        var code by remember { mutableStateOf<String?>(null) }
        var status by remember { mutableStateOf("Asking Plex for a code…") }
        var servers by remember { mutableStateOf<List<PlexResource>>(emptyList()) }
        var token by remember { mutableStateOf<String?>(null) }
        // Set once a server is picked, if it turns out to have more than one
        // music library — asked here rather than after a sync, because syncing
        // the whole server first only to narrow it afterwards is the long way
        // round on a machine with several libraries.
        var pending by remember { mutableStateOf<PendingServer?>(null) }

        LaunchedEffect(Unit) {
            val pin = PlexAccount.requestPin()
            if (pin == null) {
                status = "Couldn't reach plex.tv."
                return@LaunchedEffect
            }
            code = pin.code
            status = "Waiting for you to enter it…"
            val linked = PlexAccount.awaitToken(pin)
            if (linked == null) {
                status = "The code expired. Go back and try again."
                code = null
                return@LaunchedEffect
            }
            token = linked
            status = "Finding your servers…"
            servers = PlexAccount.resources(linked)
            status = if (servers.isEmpty()) "No servers on that account." else "Choose a server"
        }

        ListScreen(onBack = { goBack() }, title = "Link Plex") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                if (servers.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(px(40)),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(px(31)),
                        ) {
                            AppText("plex.tv/link", pxSp(LightType.COPY_PX), align = TextAlign.Center)
                            code?.let {
                                // The whole point of the screen, so it gets the
                                // size: read off this and typed somewhere else.
                                AppText(it, pxSp(122), align = TextAlign.Center)
                            }
                            AppText(status, pxSp(LightType.DETAIL_PX), dim = true, align = TextAlign.Center)
                        }
                    }
                } else if (pending != null) {
                    val choice = pending!!
                    item { SectionLabel("Which library?") }
                    items(choice.sections, key = { it.id }) { section ->
                        TextRow(title = section.name) {
                            save(choice.source.copy(libraryId = section.id))
                        }
                    }
                    item {
                        TextRow(title = "All libraries") { save(choice.source) }
                    }
                } else {
                    item { SectionLabel(status) }
                    items(servers, key = { it.clientIdentifier }) { server ->
                        TextRow(
                            title = server.name,
                            subtitle = if (server.owned) "Yours" else "Shared with you",
                            onClick = {
                                // One server at a time, and nothing opened over
                                // this page meanwhile: the answer goes back from
                                // here — see `gone`.
                                if (looking) return@TextRow
                                val accountToken = token ?: return@TextRow
                                looking = true
                                status = "Looking at ${server.name}…"
                                App.scope.launch {
                                    val next = prepare(server, accountToken)
                                    // Main, and only while this page is up —
                                    // see JellyfinLinkScreen.signIn.
                                    withContext(Dispatchers.Main.immediate) {
                                        if (gone) return@withContext
                                        looking = false
                                        if (next == null) {
                                            status = "Couldn't reach ${server.name}."
                                        } else if (next.sections.size < 2) {
                                            save(next.source)
                                        } else {
                                            pending = next
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
                item { SectionLabel("Server not on an account?") }
                item {
                    TextRow(title = "Enter address and token") {
                        if (looking) return@TextRow
                        navigateTo<Unit>({ PlexManualScreen(it) }, resultCallback = { goBack() })
                    }
                }
            }
        }
    }

    /**
     * Resolve which address answers, then ask what music libraries it has.
     *
     * The connection is settled now rather than at play time: a server
     * advertises several addresses and only some of them work from here.
     */
    private suspend fun prepare(server: PlexResource, accountToken: String): PendingServer? {
        val uri = PlexAccount.reachable(server, accountToken) ?: return null
        val found = inspectPlexServer(
            name = server.name,
            uri = uri,
            token = server.accessToken ?: accountToken,
            // An account already knows the server's id; only the typed-in path
            // has to go and ask for it.
            machineIdentifier = server.clientIdentifier,
        ) as? SignInOutcome.Found ?: return null
        val pending = found.server
        // Keep the addresses that didn't win too: the one that answers at home
        // is usually the LAN address, which is unreachable from anywhere else.
        // See MusicSource.connections.
        return pending.copy(
            source = pending.source.copy(connections = PlexAccount.candidates(server)),
        )
    }

    private fun save(source: MusicSource) {
        App.scope.launch {
            // Re-linking an already-known server (by Plex's own machine id)
            // updates that source in place rather than adding a second one —
            // see the note on AppSettings.saveSource — so the id actually made
            // active has to be whatever saveSource settled on, not the fresh
            // one this screen minted.
            val id = App.settings.saveSource(source)
            App.settings.setActiveSource(id)
        }
        goBack()
    }
}

/** A server we can reach, and the music libraries on it. */
data class PendingServer(val source: MusicSource, val sections: List<MusicFolder>)

/**
 * Everything about a server that has to be settled before it becomes a source:
 * that it answers, what it calls itself, and what music libraries it holds.
 *
 * Better found here than by a sync that fails after the source is already
 * saved — and said in a way that tells a wrong address from a wrong token.
 * Plex answers `/identity` without a token, so that settles the address, and
 * the libraries call settles the token.
 */
suspend fun inspectPlexServer(
    name: String,
    uri: String,
    token: String,
    machineIdentifier: String? = null,
): SignInOutcome {
    val client = PlexClient(uri, token, machineIdentifier.orEmpty())
    try {
        // Without the machine id nothing can be added to a playlist, so a server
        // that won't tell us who it is isn't usable as a source.
        val identity = machineIdentifier
            ?: try {
                client.publicIdentity()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val status = e.plexStatus()
                return when {
                    status != null && status in 500..599 ->
                        SignInOutcome.Missed(unavailableReason(status), answered = true)
                    e is PlexException || e is SerializationException -> SignInOutcome.Missed(NOT_PLEX, answered = true)
                    else -> SignInOutcome.Missed(unreachedReason(e), answered = false)
                }
            }
            ?: return SignInOutcome.Missed(NOT_PLEX, answered = true)
        val sections = try {
            client.getMusicFolders()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val status = e.plexStatus()
            return SignInOutcome.Settled(
                uri,
                when {
                    status in REFUSED_STATUSES -> PLEX_TOKEN_REFUSED
                    status != null -> "Plex couldn't list its libraries (error $status). Try again."
                    else -> "That address answered, then stopped before the token could be checked. Try again."
                },
            )
        }
        val source = MusicSource(
            id = newSourceId(),
            kind = SourceKind.PLEX,
            name = name,
            baseUrl = uri,
            token = token,
            machineIdentifier = identity,
            // Remembered on the source, so the Sources page can offer the same
            // switch a Navidrome's music folders get — without waiting for a
            // sync to discover what we already know here.
            libraries = sections.map { SourceLibrary(it.id, it.name) },
        )
        return SignInOutcome.Found(PendingServer(source, sections))
    } finally {
        client.close()
    }
}

private fun Throwable.plexStatus(): Int? =
    generateSequence(this) { it.cause }.take(8).filterIsInstance<PlexException>().firstOrNull()?.status

private val REFUSED_STATUSES = setOf(401, 403)

private const val PLEX_TOKEN_REFUSED = "Plex didn't accept that token."

private const val NOT_PLEX = "That address answered, but it isn't a Plex server. Check the address and port."

/** The fallback: a server address and a token, typed in. */
class PlexManualScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    // On the screen rather than in the composition, because opening the editor
    // for a field composes a new top-of-stack screen and tears this one's
    // composition down — see the same note in ServerScreen.
    private var name by mutableStateOf("")
    private var address by mutableStateOf("")
    private var token by mutableStateOf("")
    private var status by mutableStateOf<String?>(null)
    // Set once the server answers and turns out to hold more than one music
    // library, exactly as on the account path.
    private var pending by mutableStateOf<PendingServer?>(null)
    // Each check mints a fresh source id, so two taps while the first is still
    // in flight would add the same server twice — see the note in ServerScreen.
    private var checking by mutableStateOf(false)

    /** Set once this page has been popped, so an answer arriving late can't act on another. */
    private var gone = false

    override fun onScreenDestroy() {
        gone = true
    }

    @Composable
    override fun Content() {
        ListScreen(onBack = { goBack() }, title = "Plex Server") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                val choice = pending
                if (choice != null) {
                    item { SectionLabel("Which library?") }
                    items(choice.sections, key = { it.id }) { section ->
                        TextRow(title = section.name) {
                            save(choice.source.copy(libraryId = section.id))
                        }
                    }
                    item { TextRow(title = "All libraries") { save(choice.source) } }
                    return@ScrollableList
                }
                item { SectionLabel(status ?: "For a server not linked to an account") }
                item {
                    TextRow(title = "Name", value = name.ifBlank { "Required" }) {
                        edit("Name", name) { name = it }
                    }
                }
                item {
                    TextRow(
                        title = "Address",
                        value = address.ifBlank { "Required" },
                    ) { edit("Address", address) { address = it } }
                }
                item {
                    TextRow(title = "Token", value = token.ifBlank { "X-Plex-Token" }) {
                        edit("Token", token) { token = it }
                    }
                }
                item {
                    TextRow(title = if (checking) "Checking…" else "Save") {
                        if (checking) return@TextRow
                        if (name.isBlank() || address.isBlank() || token.isBlank()) {
                            status = "Fill in all three."
                            return@TextRow
                        }
                        checking = true
                        status = "Checking…"
                        App.scope.launch {
                            // Proven before it is saved: a source that can't be
                            // reached would otherwise sit there failing to sync
                            // with nothing to say why.
                            val outcome = firstAnsweringSpelling(address) { uri ->
                                inspectPlexServer(name = name.trim(), uri = uri, token = token.trim())
                            }
                            // Main, and only while this page is up — see
                            // JellyfinLinkScreen.signIn.
                            withContext(Dispatchers.Main.immediate) {
                                if (gone) return@withContext
                                checking = false
                                when (outcome) {
                                    is SignInOutcome.Found -> {
                                        val next = outcome.server
                                        // As on the Jellyfin form: the spelling that answered.
                                        address = next.source.baseUrl
                                        if (next.sections.size < 2) save(next.source) else pending = next
                                    }
                                    is SignInOutcome.Settled -> {
                                        address = outcome.uri
                                        status = outcome.reason
                                    }
                                    is SignInOutcome.Missed -> status = outcome.reason
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun edit(title: String, initial: String, onResult: (String) -> Unit) {
        // Not while a check is in flight: its answer goes back from this page,
        // and with the keyboard over it, it would close the keyboard instead —
        // and leave this page up to be signed in from a second time.
        if (checking) return
        navigateTo<String?>(
            { TextEntryScreen(it, title = title, initial = initial) },
            resultCallback = { text -> if (text != null) onResult(text) },
        )
    }

    private fun save(source: MusicSource) {
        App.scope.launch {
            // See the note in PlexLinkScreen.save: the id that ends up active
            // is whichever one saveSource kept, which may be an existing
            // source's if this same server (by machineIdentifier) was already
            // linked under a different address.
            val id = App.settings.saveSource(source)
            App.settings.setActiveSource(id)
        }
        goBack(Unit)
    }
}
