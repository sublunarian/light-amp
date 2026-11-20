package com.sublunar.amp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.sublunar.amp.App
import com.sublunar.amp.log.Log
import com.sublunar.amp.source.PhoneAccess
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.TextRole
import com.sublunar.amp.ui.components.appClickable
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.launch

/**
 * What tapping "Show Folder Covers?" does, or null when it shouldn't show.
 *
 * Albums whose cover is a cover.jpg beside the music, not inside the files,
 * need the photos permission. LightOS's prompt can't say why it's asked, so
 * the question says it first, and the prompt opens only when it is tapped.
 * Offered for the phone's own music with artwork on and music access granted,
 * and only once a song has turned up with no cover inside it; once tapped, it
 * is gone whatever the answer.
 */
@Composable
internal fun rememberFolderCoverAsk(): (() -> Unit)? {
    val phone = App.source.collectAsState().value.phoneLibrary
    val artworkOn = !App.hideArtwork.collectAsState().value
    val tapped = App.settings.folderCoverOfferTapped.state.collectAsState().value
    val needed = App.artwork.coverlessPhoneSong.collectAsState().value
    val lastSyncedMs = App.librarySync.syncState.collectAsState().value.lastSyncedMs
    val launcher = rememberPermissionRequestLauncher(phone?.coverPermission.orEmpty())
    var musicReadable by remember(phone) { mutableStateOf(false) }
    LaunchedEffect(phone, lastSyncedMs) { musicReadable = phone?.access() == PhoneAccess.GRANTED }
    if (phone == null || launcher == null || !artworkOn || tapped || !needed || !musicReadable) return null
    if (phone.hasCoverAccess()) return null
    return {
        Log.i("AmpArt", "folder covers: asking for photos access")
        launcher.launch()
        App.scope.launch { App.settings.folderCoverOfferTapped.set(true) }
    }
}

/**
 * The question, centered, on the page's own background: pinned over the top
 * of a list, the rows scroll away beneath it, so it reads as a message and
 * not as one of them.
 */
@Composable
internal fun FolderCoverQuestion(onTap: () -> Unit, modifier: Modifier = Modifier) {
    AppText(
        "Show Folder Covers?",
        pxSp(LightType.FINE_PX),
        lineHeight = pxSp(LightType.FINE_LINE_PX),
        role = TextRole.Detail,
        align = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .background(LightThemeTokens.colors.background)
            .appClickable(onTap)
            .padding(vertical = px(QUESTION_PAD_PX)),
    )
}

private const val QUESTION_PAD_PX = 28
