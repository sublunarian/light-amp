package com.sublunar.amp.source.plex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.sublunar.amp.App
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.SourceLibrary
import com.sublunar.amp.data.newSourceId
import com.sublunar.amp.source.PendingServer
import com.sublunar.amp.source.SignInOutcome
import com.sublunar.amp.source.causes
import com.sublunar.amp.source.firstAnsweringSpelling
import com.sublunar.amp.source.unavailableReason
import com.sublunar.amp.source.unreachedReason
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.sublunar.amp.ui.screens.AmpScreen
import com.thelightphone.sdk.SealedLightActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import com.sublunar.amp.ui.screens.editText
import com.sublunar.amp.ui.screens.adoptServer

/**
 * Linking a Plex account by code (see [PlexAccount]): once the code is claimed,
 * the account's servers are listed and the user picks one. A server that was
 * never claimed to an account is added on [PlexManualScreen], reached from the
 * row at the bottom.
 */
class PlexLinkScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    /** A server is being looked at; its answer will go back from this page. */
    private var looking by mutableStateOf(false)

    /** The line above the servers, which [pick] writes to as well as the code's wait. */
    private var status by mutableStateOf("Asking Plex for a code…")

    // The link's progress lives on the screen rather than in the composition,
    // which the SDK disposes while another page is on top: coming back from
    // the manual page would otherwise ask for a new code and forget the
    // account's servers.

    /** The code being waited on, kept so a page composed again waits on the same one. */
    private var pin: PlexPin? = null

    /** The code on show, until it is claimed. */
    private var code by mutableStateOf<String?>(null)

    private var token by mutableStateOf<String?>(null)
    private var servers by mutableStateOf<List<PlexResource>>(emptyList())

    /**
     * The way in without an account, offered until the code is claimed, and
     * again if the account route leads nowhere (no servers, or none that answers).
     */
    private var offerManual by mutableStateOf(true)

    /** Set once this page has been popped, so an answer arriving late can't act on another. */
    private var gone = false

    /**
     * Who this link is, to plex.tv and to the server: the code is asked for
     * under it, the token is issued to it, and the source keeps it (see
     * MusicSource.deviceId). One per page, so a code asked for again after the
     * page was rebuilt is still the same device asking.
     */
    private val clientId = PlexClient.newClientIdentifier()

    override fun onScreenDestroy() {
        gone = true
    }

    @Composable
    override fun Content() {
        LaunchedEffect(Unit) { link() }

        ListScreen(onBack = { goBack() }, title = "Link Plex") {
            ScrollableList {
                if (servers.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(px(40)),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(px(31)),
                        ) {
                            code?.let {
                                AppText("plex.tv/link", pxSp(LightType.COPY_PX), align = TextAlign.Center)
                                // Read off this screen and typed somewhere else, so it gets the size.
                                AppText(it, pxSp(122), align = TextAlign.Center)
                            }
                            AppText(status, pxSp(LightType.DETAIL_PX), dim = true, align = TextAlign.Center)
                        }
                    }
                } else {
                    item { SectionLabel(status) }
                    items(servers, key = { it.clientIdentifier }) { server ->
                        TextRow(
                            title = server.name,
                            subtitle = if (server.owned) "Yours" else "Shared with you",
                            onClick = { token?.let { pick(server, it) { offerManual = true } } },
                        )
                    }
                }
                // Never while a server is being connected to: its answer goes on from here.
                if (offerManual && !looking) {
                    item { SectionLabel("Server not on an account?") }
                    item {
                        TextRow(title = "Enter address and token") {
                            navigateTo<Unit>({ PlexManualScreen(it) }, resultCallback = { goBack() })
                        }
                    }
                }
            }
        }
    }

    /**
     * Ask for a code, wait for it to be claimed, then find the account's
     * servers. Run while the page is composed, and picked up where it was left
     * when it is composed again: the same code is waited on, and a claimed one
     * isn't asked for again.
     */
    private suspend fun link() {
        if (token != null) return
        val asked = pin ?: PlexAccount.requestPin(clientId)?.also { pin = it }
        if (asked == null) {
            status = "Couldn't reach plex.tv."
            return
        }
        code = asked.code
        status = "Waiting for you to enter it…"
        val linked = PlexAccount.awaitToken(asked, clientId)
        if (linked == null) {
            status = "The code expired. Go back and try again."
            code = null
            pin = null
            return
        }
        token = linked
        offerManual = false
        // The code has done its job; from here the page only says what is happening.
        code = null
        status = "Finding your servers…"
        val found = PlexAccount.resources(linked, clientId)
        val only = found.singleOrNull()
        if (only != null) {
            // One server is no choice: go straight on to it, and list it
            // only if it doesn't answer, to try again from.
            status = "Connecting to ${only.name}…"
            pick(only, linked) {
                servers = found
                offerManual = true
            }
        } else {
            servers = found
            status = if (found.isEmpty()) "No servers on that account." else "Choose a server"
            if (found.isEmpty()) offerManual = true
        }
    }

    /**
     * Look at [server] and, if it answers, carry on to its libraries (picked
     * before its first sync, see finishAddingSource). One server at a time, and
     * nothing opened over this page meanwhile: the answer goes on from here.
     * [onMissed] runs if it doesn't answer, after the status says why.
     */
    private fun pick(server: PlexResource, accountToken: String, onMissed: () -> Unit) {
        if (looking) return
        looking = true
        status = "Looking at ${server.name}…"
        App.scope.launch {
            val outcome = prepare(server, accountToken)
            // Main, and only while this page is up: see JellyfinLinkScreen.signIn.
            withContext(Dispatchers.Main.immediate) {
                if (gone) return@withContext
                looking = false
                when (outcome) {
                    is SignInOutcome.Found -> adoptServer(outcome.server) { gone }
                    is SignInOutcome.Settled -> {
                        status = outcome.reason
                        onMissed()
                    }
                    is SignInOutcome.Missed -> {
                        status = outcome.reason
                        onMissed()
                    }
                }
            }
        }
    }

    /**
     * Resolve which address answers, then ask what music libraries it has.
     * Settled now rather than at play time: only some of a server's addresses
     * work from here.
     */
    private suspend fun prepare(server: PlexResource, accountToken: String): SignInOutcome {
        val uri = PlexAccount.reachable(server, accountToken, clientId)
            ?: return SignInOutcome.Missed("Couldn't reach ${server.name}.", answered = false)
        val outcome = inspectPlexServer(
            name = server.name,
            uri = uri,
            token = server.accessToken ?: accountToken,
            clientIdentifier = clientId,
            // An account already knows the server's id; only the typed-in path asks for it.
            machineIdentifier = server.clientIdentifier,
        )
        if (outcome !is SignInOutcome.Found) return outcome
        val pending = outcome.server
        // Keep the addresses that didn't win too: the LAN one that answers at
        // home is dead anywhere else (see MusicSource.connections).
        return SignInOutcome.Found(
            pending.copy(source = pending.source.copy(connections = PlexAccount.candidates(server))),
        )
    }
}

/**
 * Everything about a server that has to be settled before it becomes a source:
 * that it answers, what it calls itself, and what music libraries it holds,
 * said in a way that tells a wrong address from a wrong token. Plex answers
 * `/identity` without a token, so that settles the address, and the libraries
 * call settles the token.
 */
suspend fun inspectPlexServer(
    name: String,
    uri: String,
    token: String,
    /** The new link's own identifier, kept on the source — see MusicSource.deviceId. */
    clientIdentifier: String,
    machineIdentifier: String? = null,
): SignInOutcome {
    val client = PlexClient(uri, token, machineIdentifier.orEmpty(), clientIdentifier = clientIdentifier)
    try {
        // Without the machine id nothing can be added to a playlist, so a server
        // that won't say who it is isn't usable as a source.
        val identity = machineIdentifier
            ?: try {
                client.publicIdentity()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return identityMissed(e)
            }
            ?: return SignInOutcome.Missed(NOT_PLEX, answered = true)
        val sections = try {
            client.getMusicFolders()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SignInOutcome.Settled(uri, librariesFailed(e))
        }
        val source = MusicSource(
            id = newSourceId(),
            kind = SourceKind.PLEX,
            name = name,
            baseUrl = uri,
            token = token,
            machineIdentifier = identity,
            deviceId = clientIdentifier,
            // So the Sources page can offer the library switch before the first sync.
            libraries = sections.map { SourceLibrary(it.id, it.name) },
        )
        return SignInOutcome.Found(PendingServer(source, sections))
    } finally {
        client.close()
    }
}

private fun identityMissed(e: Exception): SignInOutcome.Missed {
    val status = e.plexStatus()
    return when {
        status != null && status in 500..599 -> SignInOutcome.Missed(unavailableReason(status), answered = true)
        e is PlexException || e is SerializationException -> SignInOutcome.Missed(NOT_PLEX, answered = true)
        else -> SignInOutcome.Missed(unreachedReason(e), answered = false)
    }
}

private fun librariesFailed(e: Exception): String {
    val status = e.plexStatus()
    return when {
        status in REFUSED_STATUSES -> PLEX_TOKEN_REFUSED
        status != null -> "Plex couldn't list its libraries (error $status). Try again."
        else -> "That address answered, then stopped before the token could be checked. Try again."
    }
}

private fun Throwable.plexStatus(): Int? =
    causes().filterIsInstance<PlexException>().firstOrNull()?.status

private val REFUSED_STATUSES = setOf(401, 403)

private const val PLEX_TOKEN_REFUSED = "Plex didn't accept that token."

private const val NOT_PLEX = "That address answered, but it isn't a Plex server. Check the address and port."

/** The fallback: a server address and a token, typed in. */
class PlexManualScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    // On the screen rather than in the composition: opening the editor for a
    // field composes a new top-of-stack screen and tears this one's down.
    private var name by mutableStateOf("")
    private var address by mutableStateOf("")
    private var token by mutableStateOf("")
    private var status by mutableStateOf<String?>(null)
    // Each check mints a fresh source id, so two taps while the first is still
    // in flight would add the same server twice.
    private var checking by mutableStateOf(false)

    /** Who this link is to the server, kept on the source: see MusicSource.deviceId. */
    private val clientId = PlexClient.newClientIdentifier()

    /** Set once this page has been popped, so an answer arriving late can't act on another. */
    private var gone = false

    override fun onScreenDestroy() {
        gone = true
    }

    @Composable
    override fun Content() {
        ListScreen(onBack = { goBack() }, title = "Plex Server") {
            ScrollableList {
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
                            val outcome = firstAnsweringSpelling(address) { uri ->
                                inspectPlexServer(
                                    name = name.trim(),
                                    uri = uri,
                                    token = token.trim(),
                                    clientIdentifier = clientId,
                                )
                            }
                            // Main, and only while this page is up: see JellyfinLinkScreen.signIn.
                            withContext(Dispatchers.Main.immediate) {
                                if (gone) return@withContext
                                checking = false
                                when (outcome) {
                                    is SignInOutcome.Found -> {
                                        val next = outcome.server
                                        // The spelling that answered.
                                        address = next.source.baseUrl
                                        adoptServer(next) { gone }
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
        // and with the keyboard over it, it would close the keyboard instead
        // and leave this page up to be signed in from a second time.
        if (checking) return
        editText(title, initial, onResult)
    }
}
