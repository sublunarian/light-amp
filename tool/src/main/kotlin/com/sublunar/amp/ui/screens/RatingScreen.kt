package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.data.WriteResult
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "★★★☆☆" for a rating, so the choice reads at a glance. */
fun ratingStars(stars: Int): String =
    "★".repeat(stars.coerceIn(0, 5)) + "☆".repeat((5 - stars).coerceIn(0, 5))

/**
 * Pick a 1–5 star rating, or clear it. The rating shows at once and goes to the
 * server after (queued when it can't be reached, as a like is). The page waits only
 * to report a refusal: the rating is back to what it was, and the page stays open
 * with the reason.
 */
class RatingScreen(
    sealed: SealedLightActivity,
    private val id: String,
    private val title: String,
    private val current: Int,
    private val isAlbum: Boolean,
) : SimpleLightScreen<Unit>(sealed) {

    /** Set once this page has been popped, so a save finishing late can't pop another. */
    private var gone = false

    override fun onScreenDestroy() {
        gone = true
    }

    // While casting, the rocker belongs to the speaker (see handleVolumeKey).
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        var status by remember { mutableStateOf<String?>(null) }
        var saving by remember { mutableStateOf(false) }

        ListScreen(onBack = { goBack() }, title = "Rating") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                item { SectionLabel(status ?: title) }
                items(6) { i ->
                    // Listed 5 stars down to none, so the common choices are first.
                    val stars = 5 - i
                    TextRow(
                        title = if (stars == 0) "No rating" else ratingStars(stars),
                        onClick = {
                            if (saving) return@TextRow
                            saving = true
                            status = "Saving…"
                            App.scope.launch {
                                val result = App.library.setRating(id, stars, isAlbum, previous = current)
                                // Back on Main, and only while this page still shows:
                                // App.scope is Default, and goBack pops whatever is on
                                // top, so after a slow save it could pop the root and
                                // tear down off Main, where releasing the player
                                // throws. A refusal is still told wherever they are.
                                withContext(Dispatchers.Main.immediate) {
                                    val refused = result as? WriteResult.Refused
                                    if (gone) {
                                        refused?.let { App.tell("Couldn't save\n${it.reason}") }
                                        return@withContext
                                    }
                                    if (refused == null) goBack() else { status = refused.reason; saving = false }
                                }
                            }
                        },
                        trailing = {
                            if (stars == current) LightIcon(LightIcons.ACCEPT, size = 1.4f)
                        },
                    )
                }
            }
        }
    }
}
