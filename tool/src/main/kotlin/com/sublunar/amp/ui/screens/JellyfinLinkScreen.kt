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
import com.sublunar.amp.data.JellyfinSignIn
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
import kotlinx.coroutines.launch

/**
 * A Jellyfin server, its address and an account on it.
 *
 * One screen rather than Plex's two, because Jellyfin has no account service in
 * front of the server: there is nothing to link to and no list of machines to
 * choose from. You know where your server is, and it knows who you are.
 */
class JellyfinLinkScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    // On the screen rather than in the composition: opening the keyboard for a
    // field composes a new top-of-stack screen and tears this one's composition
    // down, taking any remembered state with it. Same note as ServerScreen.
    private var address by mutableStateOf("")
    private var username by mutableStateOf("")
    private var password by mutableStateOf("")
    private var status by mutableStateOf<String?>(null)
    /** Set once the server answers and turns out to hold more than one library. */
    private var pending by mutableStateOf<PendingServer?>(null)
    /** Each check mints a fresh source id, so a second tap would add it twice. */
    private var checking by mutableStateOf(false)

    @Composable
    override fun Content() {
        ListScreen(onBack = { goBack() }, title = "Jellyfin") {
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
                item { SectionLabel(status ?: "Your server and an account on it") }
                item {
                    TextRow(
                        title = "Address",
                        subtitle = address.ifBlank { "http://192.168.1.10:8096" },
                    ) { edit("Address", address) { address = it } }
                }
                item {
                    TextRow(title = "Username", subtitle = username.ifBlank { "Required" }) {
                        edit("Username", username) { username = it }
                    }
                }
                item {
                    // Jellyfin allows an empty password, and a server set up that
                    // way is a normal thing to point at — so this is not required.
                    TextRow(
                        title = "Password",
                        subtitle = if (password.isBlank()) "Leave blank if none" else "••••••••",
                    ) { edit("Password", password) { password = it } }
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
            val next = inspectJellyfinServer(
                uri = address.trim().trimEnd('/'),
                username = username.trim(),
                password = password,
            )
            checking = false
            when {
                next == null -> status = "Couldn't sign in with that address and account."
                next.sections.isEmpty() -> status = "Signed in, but that account has no music library."
                next.sections.size < 2 -> save(next.source)
                else -> pending = next
            }
        }
    }

    private fun edit(title: String, initial: String, onResult: (String) -> Unit) {
        navigateTo<String?>(
            { TextEntryScreen(it, title = title, initial = initial) },
            resultCallback = { text -> if (text != null) onResult(text) },
        )
    }

    private fun save(source: MusicSource) {
        App.scope.launch {
            val id = App.settings.saveSource(source)
            App.settings.setActiveSource(id)
        }
        goBack(Unit)
    }
}

/**
 * Everything about a Jellyfin server that has to be settled before it becomes a
 * source: that it answers, who we are on it, what it calls itself, and what
 * music libraries that account can see.
 *
 * Null when the address and the account don't get us in — better found here
 * than by a sync that fails after the source is already saved.
 */
suspend fun inspectJellyfinServer(
    uri: String,
    username: String,
    password: String,
): PendingServer? {
    val signIn = JellyfinSignIn.authenticate(uri, username, password) ?: return null
    val client = signIn.client
    try {
        val sections = runCatching { client.getMusicFolders() }.getOrNull() ?: return null
        val source = MusicSource(
            id = newSourceId(),
            kind = SourceKind.JELLYFIN,
            // The server's own name where it gave one, so the Sources page
            // reads as the server rather than as its IP address.
            name = signIn.serverName.ifBlank { MusicSource.nameFor(uri) },
            baseUrl = uri,
            username = username,
            token = signIn.token,
            userId = signIn.userId,
            // Remembered on the source so the Sources page can offer the same
            // switch a Navidrome's music folders get, without waiting for a
            // sync to discover what we already know here.
            libraries = sections.map { SourceLibrary(it.id, it.name) },
        )
        return PendingServer(source, sections)
    } finally {
        client.close()
    }
}
