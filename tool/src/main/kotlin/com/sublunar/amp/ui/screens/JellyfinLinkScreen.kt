package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.data.JellyfinClient
import com.sublunar.amp.data.JellyfinSignIn
import com.sublunar.amp.data.JellyfinSignInResult
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.SourceLibrary
import com.sublunar.amp.data.newSourceId
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * A Jellyfin server, its address and an account on it.
 *
 * One screen rather than Plex's two, because Jellyfin has no account service in
 * front of the server: there is nothing to link to and no list of machines to
 * choose from. You know where your server is, and it knows who you are.
 *
 * With [again], the same page signs an existing source in again — after the
 * server stopped taking its token, or to reach it at a new address — and the
 * source keeps its library and its downloads. Logging out and adding it anew
 * was the only way back before, and that deleted them.
 */
class JellyfinLinkScreen(
    sealed: SealedLightActivity,
    private val again: MusicSource? = null,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    // On the screen rather than in the composition: opening the keyboard for a
    // field composes a new top-of-stack screen and tears this one's composition
    // down, taking any remembered state with it. Same note as ServerScreen.
    private var address by mutableStateOf(again?.baseUrl.orEmpty())
    private var username by mutableStateOf(again?.username.orEmpty())
    private var password by mutableStateOf("")
    private var status by mutableStateOf<String?>(null)
    /** Each check mints a fresh source id, so a second tap would add it twice. */
    private var checking by mutableStateOf(false)

    /** Set once this page has been popped, so an answer arriving late can't act on another. */
    private var gone = false

    override fun onScreenDestroy() {
        gone = true
    }

    @Composable
    override fun Content() {
        ListScreen(onBack = { goBack() }, title = again?.name ?: "Jellyfin") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                item {
                    SectionLabel(
                        status ?: if (again != null) "Sign in again, keeping its library and downloads"
                        else "Your server and an account on it",
                    )
                }
                item {
                    TextRow(
                        title = "Address",
                        value = address.ifBlank { "Required" },
                    ) { edit("Address", address) { address = it } }
                }
                item {
                    TextRow(title = "Username", value = username.ifBlank { "Required" }) {
                        edit("Username", username) { username = it }
                    }
                }
                item {
                    // Jellyfin allows an empty password, and a server set up that
                    // way is a normal thing to point at — so this is not required.
                    // Seen while typed and never again, so the keyboard opens
                    // empty; what is submitted replaces it, and nothing is none.
                    TextRow(
                        title = "Password",
                        value = if (password.isBlank()) "Leave blank if none" else "••••••••",
                    ) { edit("Password", "") { password = it } }
                }
                item {
                    TextRow(title = if (checking) "Checking…" else "Sign In") {
                        if (checking) return@TextRow
                        if (address.isBlank() || username.isBlank()) {
                            status = "An address and a username, at least."
                            return@TextRow
                        }
                        signIn()
                    }
                }
            }
        }
    }

    private fun signIn() {
        checking = true
        status = "Checking…"
        App.scope.launch {
            // Proven before it is saved: a source that can't be reached would
            // otherwise sit there failing to sync with nothing to say why.
            // A source signed in again stays the device it was, so the server
            // replaces that sign-in rather than keeping both. One from before
            // devices were per source moves off the id every install shared.
            val deviceId = again?.deviceId?.ifBlank { null } ?: JellyfinClient.newDeviceId()
            val outcome = firstAnsweringSpelling(address) { uri ->
                inspectJellyfinServer(uri = uri, username = username.trim(), password = password, deviceId = deviceId)
            }
            // Back on Main, and only if this page is still up: App.scope is
            // Default, and finishing the sign-in navigates — which, after the
            // user has left, would act on whatever is on top, the root
            // included, off the main thread. Leaving the sign-in is also
            // abandoning it.
            withContext(Dispatchers.Main.immediate) {
                if (gone) return@withContext
                checking = false
                when (outcome) {
                    is SignInOutcome.Found -> {
                        val next = outcome.server
                        // The spelling that answered, https:// and all, so the
                        // form shows what was actually reached.
                        address = next.source.baseUrl
                        when {
                            again != null -> signedInAgain(again, next.source)
                            next.sections.isEmpty() -> status = "Signed in, but that account has no music library."
                            // Its libraries are picked after this, before its
                            // first sync — see finishAddingSource.
                            else -> adoptServer(next) { gone }
                        }
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

    /**
     * The source takes the new sign-in and keeps everything else. Only the same
     * account, though: another one is another library, which belongs in a
     * source of its own rather than in this one's cache and downloads.
     */
    private fun signedInAgain(old: MusicSource, fresh: MusicSource) {
        if (fresh.userId != old.userId) {
            status = "That's a different account. Add it from Sources as a new server instead."
            return
        }
        checking = true
        App.scope.launch {
            App.settings.saveSource(
                old.copy(
                    baseUrl = fresh.baseUrl,
                    username = fresh.username,
                    token = fresh.token,
                    deviceId = fresh.deviceId,
                    libraries = fresh.libraries.ifEmpty { old.libraries },
                ),
            )
            withContext(Dispatchers.Main.immediate) {
                if (App.source.value.id == old.id) App.library.syncInBackground(force = true)
                checking = false
                if (!gone) goBack()
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

}

/**
 * Everything about a Jellyfin server that has to be settled before it becomes a
 * source: that it answers, who we are on it, what it calls itself, and what
 * music libraries that account can see.
 *
 * Better found here than by a sync that fails after the source is already
 * saved — and said in a way that tells a wrong address from a wrong account.
 */
suspend fun inspectJellyfinServer(
    uri: String,
    username: String,
    password: String,
    deviceId: String,
): SignInOutcome {
    val signIn = when (val result = JellyfinSignIn.authenticate(uri, username, password, deviceId = deviceId)) {
        is JellyfinSignInResult.SignedIn -> result.session
        is JellyfinSignInResult.Unreached ->
            return SignInOutcome.Missed(unreachedReason(result.error), answered = false)
        JellyfinSignInResult.NotJellyfin -> return SignInOutcome.Missed(
            "That address answered, but it isn't a Jellyfin server. Check the address and port.",
            answered = true,
        )
        is JellyfinSignInResult.Unavailable ->
            return SignInOutcome.Missed(unavailableReason(result.status), answered = true)
        is JellyfinSignInResult.Refused ->
            return SignInOutcome.Settled(result.baseUrl, "Jellyfin didn't accept that username and password.")
        is JellyfinSignInResult.Failed -> return SignInOutcome.Settled(
            result.baseUrl,
            when (val status = result.status) {
                null -> "The sign-in didn't finish. Try again."
                // Followed for the address check but not for the sign-in: a
                // redirect to another host, which isn't followed with a password.
                in 300..399 -> "That address sends the sign-in somewhere else. Type the address it leads to."
                else -> "Jellyfin couldn't sign that account in (error $status). Try again."
            },
        )
    }
    val client = signIn.client
    try {
        val sections = runCatching { client.getMusicFolders() }.getOrNull()
            ?: return SignInOutcome.Settled(signIn.baseUrl, "Signed in, but the server didn't list its libraries. Try again.")
        val source = MusicSource(
            id = newSourceId(),
            kind = SourceKind.JELLYFIN,
            // The server's own name where it gave one, so the Sources page
            // reads as the server rather than as its IP address.
            name = signIn.serverName.ifBlank { MusicSource.nameFor(signIn.baseUrl) },
            baseUrl = signIn.baseUrl,
            username = username,
            token = signIn.token,
            userId = signIn.userId,
            deviceId = deviceId,
            // Remembered on the source so the Sources page can offer the same
            // switch a Navidrome's music folders get, without waiting for a
            // sync to discover what we already know here.
            libraries = sections.map { SourceLibrary(it.id, it.name) },
        )
        return SignInOutcome.Found(PendingServer(source, sections))
    } finally {
        client.close()
    }
}
