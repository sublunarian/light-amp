package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import com.sublunar.amp.App
import com.sublunar.amp.ui.PlayerTheme
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.AppProgressBar
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.sublunar.amp.source.plex.PlexPlayer
import com.sublunar.amp.playback.PlaybackController
import com.sublunar.amp.playback.output.OutputTarget
import com.sublunar.amp.playback.output.dlna.DlnaFamily
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.sublunar.amp.ui.components.appClickable
import kotlin.math.roundToInt

/**
 * Output + volume sheet reached from the Now Playing transport.
 *
 * The volume fader drives the phone's own media volume. Speaker-vs-Bluetooth
 * routing is LightOS's job (a tool has no access to the audio-routing APIs), so
 * that part of the list is informational. Network speakers found over DLNA *can*
 * be switched to (see `DlnaFamily`), and so can other Plex players (an Apple TV's
 * Plex app, say) when the active source is Plex, over Plex's own Companion
 * protocol.
 */
class OutputScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val volume by App.playback.volume.collectAsState()
        val active by App.playback.active.collectAsState()
        val plexCasting by App.playback.plexPlayer.collectAsState()
        val plexVolume by App.playback.plexVolume.collectAsState()
        var renderers by remember { mutableStateOf<List<OutputTarget>>(emptyList()) }
        var plexPlayers by remember { mutableStateOf<List<PlexPlayer>>(emptyList()) }
        var scanning by remember { mutableStateOf(true) }

        // Discovery is a few seconds of UDP waiting, so it runs once on open and
        // is repeatable from the Scan row rather than on every recomposition.
        // The Plex ask is one HTTP round trip, so it goes first and its players
        // appear while the UDP wait is still on.
        var scanToken by remember { mutableStateOf(0) }
        LaunchedEffect(scanToken) {
            scanning = true
            plexPlayers = App.playback.findPlexPlayers()
            renderers = App.playback.find(DlnaFamily)
            scanning = false
        }

        // A fader with nothing to move: an Apple TV feeding a receiver reports
        // a volume it doesn't own, takes the command and changes nothing. Once
        // that is established the control is disabled rather than left to lie.
        // Dimmed rather than hidden, so the page doesn't jump.
        val faderLive = plexCasting == null ||
            plexVolume != PlaybackController.PlexVolume.NotOurs

        PlayerTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                AppHeader(onBack = { goBack() }, title = "Output")

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
                            if (faderLive) "${(volume * 100).roundToInt()}%" else "on the TV",
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
                            selected = active == null && plexCasting == null,
                        ) { App.playback.playHere() }
                    }
                    item {
                        OutputRow(AppIcons.Bluetooth, "Bluetooth", selected = false, enabled = false)
                    }

                    item { SectionLabel(if (scanning) "Network — searching…" else "Network") }
                    items(renderers, key = { it.id }) { renderer ->
                        val selected = active?.family === DlnaFamily && active?.target?.id == renderer.id
                        OutputRow(
                            icon = AppIcons.Cast,
                            label = renderer.name,
                            selected = selected,
                        ) {
                            if (selected) {
                                App.playback.playHere()
                            } else {
                                App.playback.castTo(DlnaFamily, renderer)
                            }
                        }
                    }
                    items(plexPlayers, key = { "plex-" + it.id }) { p ->
                        OutputRow(
                            icon = AppIcons.Cast,
                            label = p.name,
                            selected = plexCasting?.id == p.id,
                        ) {
                            if (plexCasting?.id == p.id) {
                                App.playback.stopPlexCasting()
                            } else {
                                App.playback.castToPlex(p)
                            }
                        }
                    }
                    if (!scanning && renderers.isEmpty() && plexPlayers.isEmpty()) {
                        item {
                            AppText(
                                "No network speakers found. They need to be on the same Wi-Fi.",
                                pxSp(LightType.DETAIL_PX),
                                lineHeight = pxSp(LightType.DETAIL_LINE_PX),
                                dim = true,
                                modifier = Modifier.padding(horizontal = px(80), vertical = px(20)),
                            )
                        }
                    }
                    if (!scanning) {
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

    @Composable
    private fun OutputRow(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        label: String,
        selected: Boolean,
        enabled: Boolean = true,
        onClick: (() -> Unit)? = null,
    ) {
        // The same row every other menu draws, with the route's glyph in
        // front of it — one component, not a rendering of its own.
        TextRow(
            title = label,
            leading = {
                AppIcon(icon, size = px(56), modifier = if (selected) Modifier else Modifier.alpha(0.5f))
            },
            trailing = { if (selected) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
            onClick = if (enabled) onClick else null,
        )
    }
}
