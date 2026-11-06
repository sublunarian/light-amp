package com.sublunar.amp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sublunar.amp.App
import com.sublunar.amp.log.Log
import com.sublunar.amp.log.logName
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The phone's own log, said plainly, with the two things a person can do with
 * it: put a copy where a computer can read it, or erase it. Nothing leaves the
 * phone from here; a copy in Download is still on the phone. Opened by
 * holding the name or version on About.
 */
class LogScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    // On the screen, not in a remember: the confirmation tears the composition down.
    private var said by mutableStateOf<String?>(null)

    @Composable
    override fun Content() {
        ListScreen(onBack = { goBack() }, title = "Log", headingRows = true) {
            ScrollableList {
                item { SectionLabel(ABOUT) }
                item {
                    TextRow(title = "Save a Copy", subtitle = "To Download/Amp on this phone") {
                        App.scope.launch {
                            val result = withContext(Dispatchers.IO) { saveCopy() }
                            withContext(Dispatchers.Main) { said = result }
                        }
                    }
                }
                item {
                    TextRow(title = "Clear Log") {
                        navigateTo<Boolean>(
                            {
                                ConfirmScreen(
                                    it,
                                    title = "Clear Log",
                                    message = "This erases Amp's log on this phone and the copies " +
                                        "it saved to Download/Amp.",
                                    confirmLabel = "Clear",
                                )
                            },
                            resultCallback = { confirmed ->
                                if (confirmed == true) {
                                    App.scope.launch {
                                        val result = withContext(Dispatchers.IO) { clear() }
                                        withContext(Dispatchers.Main) { said = result }
                                    }
                                }
                            },
                        )
                    }
                }
                said?.let { item { SectionLabel(it) } }
            }
        }
    }

    private fun saveCopy(): String {
        val name = "amp-log-${LocalDateTime.now().format(FILE_TIME)}.txt"
        return try {
            Log.copyTo(File(SAVED_DIR, name), COPY_NOTE)
                ?.let { "Saved as Download/Amp/$name. A computer connected by USB can read it, and it stays after Amp is removed. Read it before you post it anywhere public." }
                ?: "The log isn't open yet."
        } catch (e: Exception) {
            Log.w("AmpLog", "copy not saved: ${e.logName()}")
            "Couldn't save to Download."
        }
    }

    private fun clear(): String {
        Log.clear()
        // Only Amp's own copies can be deleted; one saved before Amp was
        // reinstalled belongs to no app now, and stays.
        val saved = SAVED_DIR.listFiles { f -> f.name.startsWith("amp-log-") && f.name.endsWith(".txt") }.orEmpty()
        val kept = saved.count { !it.delete() }
        return if (kept == 0) "Cleared." else "Cleared. $kept saved ${if (kept == 1) "copy" else "copies"} in Download/Amp must be deleted from a computer."
    }

    private companion object {
        val SAVED_DIR = File("/storage/emulated/0/Download/Amp")
        val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        const val ABOUT =
            "Amp keeps a log on this phone of what it did and when: syncs, network changes, " +
                "playback starting and failing, downloads, casting. It shows when you used Amp, " +
                "your servers' kinds and versions and how big your library is. It never holds " +
                "logins, server addresses, or song, album, artist or playlist names. Lines are " +
                "kept up to two weeks, 1 MB at most, and never sent anywhere."

        const val COPY_NOTE =
            "Amp log. It lists what Amp did and when. It holds no logins, server addresses or " +
                "song, album, artist or playlist names. Read it before posting it publicly."
    }
}
