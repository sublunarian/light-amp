package com.sublunar.amp.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import com.sublunar.amp.App
import com.sublunar.amp.playback.output.OutputTarget
import com.sublunar.amp.playback.output.VolumeControl
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.AppProgressBar
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.SealedLightActivity
import kotlin.math.roundToInt

/**
 * Output and volume, reached from the player.
 *
 * The fader drives the phone's media volume, or the output's while the sound is
 * elsewhere. Speaker-vs-Bluetooth routing is LightOS's job (a tool has no access
 * to the audio-routing APIs), so that part of the list is informational. Below
 * it is everywhere the sound can be switched to: speakers on the network and the
 * active source's own players. The page names none of the kinds — see
 * OutputFamilies.
 */
class OutputScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    // A speaker left paused long enough is let go before the page names it.
    override fun willShow() {
        App.playback.forgetPausedSpeaker()
    }

    @Composable
    override fun Content() {
        val volume by App.playback.volume.collectAsState()
        val active by App.playback.active.collectAsState()
        // Speakers on the network are out of reach off Wi-Fi: their rows go,
        // and the search runs again the moment the network is back.
        val rules by App.rules.collectAsState()
        val lanUsable = rules.mayUseLan
        // Keyed by the output, so a switch straight from one to another starts
        // from the new one's answer instead of holding the last one's for a frame.
        val control = active?.volumeControl
        val volumeControl = key(control) { control?.collectAsState()?.value }
        val families = remember { App.playback.outputFamilies() }
        var found by remember { mutableStateOf<Map<String, List<OutputTarget>>>(emptyMap()) }
        var scanning by remember { mutableStateOf(true) }

        // Discovery is a few seconds of UDP waiting, so it runs once on open and
        // again from the Scan row. A source's own players are one HTTP round
        // trip, so they are asked first — see searchOrder.
        var scanToken by remember { mutableStateOf(0) }
        LaunchedEffect(scanToken, lanUsable) {
            scanning = true
            for (family in families.searchOrder) found = found + (family.id to App.playback.find(family))
            scanning = false
        }
        val lanHidden = !lanUsable && families.listOrder.any { App.playback.needsLan(it) }
        val searchable = families.listOrder.any { lanUsable || !App.playback.needsLan(it) }

        // A fader with nothing to move (an Apple TV feeding a receiver reports a
        // volume it doesn't own) is disabled, and dimmed rather than hidden so
        // the page doesn't jump.
        val faderLive = volumeControl != VolumeControl.NotOurs

        ListScreen(onBack = { goBack() }, title = "Output") {
            Column(
                modifier = Modifier
                    .padding(horizontal = px(80), vertical = px(32))
                    .alpha(if (faderLive) 1f else 0.35f),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    AppText("Volume", pxSp(LightType.DETAIL_PX))
                    AppText(
                        if (faderLive) "${(volume * 100).roundToInt()}%" else active?.family?.volumeElsewhere.orEmpty(),
                        pxSp(LightType.DETAIL_PX),
                        dim = true,
                    )
                }
                Spacer(Modifier.height(px(26)))
                VolumeFader(volume, enabled = faderLive)
            }

            Spacer(Modifier.height(px(20)))
            LazyColumn(modifier = Modifier.weight(1f)) {
                item { SectionLabel("Playing on") }
                item {
                    OutputRow(
                        AppIcons.Smartphone,
                        "This device",
                        selected = active == null,
                    ) { App.playback.playHere() }
                }
                item {
                    OutputRow(AppIcons.Bluetooth, "Bluetooth", selected = false, enabled = false)
                }

                item { SectionLabel(if (scanning && searchable) "Network — searching…" else "Network") }
                for (family in families.listOrder) {
                    if (!lanUsable && App.playback.needsLan(family)) continue
                    // What is playing now is listed at once, before any
                    // search has answered, and once, whatever it finds.
                    val playing = active?.takeIf { it.family === family }?.target
                    val listed = found[family.id].orEmpty()
                    val rows = if (playing != null && listed.none { it.id == playing.id }) listOf(playing) + listed else listed
                    items(rows, key = { "${family.id}:${it.id}" }) { target ->
                        val selected = active?.family === family && active?.target?.id == target.id
                        OutputRow(
                            icon = AppIcons.Cast,
                            label = target.name,
                            selected = selected,
                        ) {
                            if (selected) {
                                App.playback.playHere()
                            } else if (!App.playback.castTo(family, target)) {
                                // Out of reach after all (the network went, or
                                // nothing can steer it): the row goes, and
                                // the search runs again.
                                found = found + (family.id to listed.filter { it.id != target.id })
                                scanToken++
                            }
                        }
                    }
                }
                if (lanHidden) {
                    item { OutputNote("Network speakers need Wi-Fi.") }
                } else if (!scanning && active == null && found.values.all { it.isEmpty() }) {
                    item { OutputNote("No network speakers found. They need to be on the same Wi-Fi.") }
                }
                if (!scanning && searchable) {
                    item {
                        OutputRow(AppIcons.Refresh, "Scan again", selected = false) { scanToken++ }
                    }
                }

                item {
                    AppText(
                        "Bluetooth speakers and headphones play automatically when they're " +
                            "paired — Light handles that routing, so it can't be switched here.",
                        pxSp(LightType.DETAIL_PX),
                        lineHeight = pxSp(LightType.DETAIL_LINE_PX),
                        dim = true,
                        modifier = Modifier.padding(horizontal = px(80), vertical = px(31)),
                    )
                }
            }
        }
    }

    @Composable
    private fun VolumeFader(volume: Float, enabled: Boolean = true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(px(31)),
        ) {
            AppIcon(AppIcons.VolumeDown, size = px(56))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(px(71))
                    // Not merely dimmed: a control that can't do anything
                    // shouldn't move under the thumb either.
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            fun ratioAt(x: Float) = (x / size.width).coerceIn(0f, 1f)
                            var r = ratioAt(down.position.x)
                            App.playback.setVolume(r)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                r = ratioAt(change.position.x)
                                App.playback.setVolume(r)
                                if (!change.pressed) break
                                change.consume()
                            }
                            App.playback.setVolume(r)
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                AppProgressBar(volume)
            }
            AppIcon(AppIcons.VolumeUp, size = px(56))
        }
    }

    /** A line of plain words under the rows, dimmed as the page's other notes are. */
    @Composable
    private fun OutputNote(words: String) {
        AppText(
            words,
            pxSp(LightType.DETAIL_PX),
            lineHeight = pxSp(LightType.DETAIL_LINE_PX),
            dim = true,
            modifier = Modifier.padding(horizontal = px(80), vertical = px(20)),
        )
    }

    @Composable
    private fun OutputRow(
        icon: ImageVector,
        label: String,
        selected: Boolean,
        enabled: Boolean = true,
        onClick: (() -> Unit)? = null,
    ) {
        TextRow(
            title = label,
            leading = {
                AppIcon(icon, size = px(56), modifier = if (selected) Modifier else Modifier.alpha(0.5f))
            },
            checked = selected,
            onClick = if (enabled) onClick else null,
        )
    }
}
