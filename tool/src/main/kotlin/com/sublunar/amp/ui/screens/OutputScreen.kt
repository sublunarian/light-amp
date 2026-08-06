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
import com.sublunar.amp.ui.n
import com.sublunar.amp.ui.nSp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.cast.DlnaRenderer
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
 * be switched to — see the temporary cast support in `DlnaCast`.
 */
class OutputScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val volume by App.playback.volume.collectAsState()
        val casting by App.playback.castRenderer.collectAsState()
        var renderers by remember { mutableStateOf<List<DlnaRenderer>>(emptyList()) }
        var scanning by remember { mutableStateOf(true) }

        // Discovery is a few seconds of UDP waiting, so it runs once on open and
        // is repeatable from the Scan row rather than on every recomposition.
        var scanToken by remember { mutableStateOf(0) }
        LaunchedEffect(scanToken) {
            scanning = true
            renderers = App.playback.findCastDevices()
            scanning = false
        }

        PlayerTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                AppHeader(onBack = { goBack() }, title = "Output")

                Column(modifier = Modifier.padding(horizontal = n(20), vertical = n(10))) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        AppText("Volume", nSp(16))
                        AppText("${(volume * 100).roundToInt()}%", nSp(16), dim = true)
                    }
                    Spacer(Modifier.height(n(10)))
                    VolumeFader(volume)
                }

                Spacer(Modifier.height(n(8)))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    item { SectionLabel("Playing on") }
                    item {
                        OutputRow(AppIcons.Smartphone, "This device", selected = casting == null) {
                            if (casting != null) App.playback.stopCasting()
                        }
                    }
                    item {
                        OutputRow(AppIcons.Bluetooth, "Bluetooth", selected = false, enabled = false)
                    }

                    item { SectionLabel(if (scanning) "Network — searching…" else "Network") }
                    items(renderers, key = { it.id }) { renderer ->
                        OutputRow(
                            icon = AppIcons.Cast,
                            label = renderer.name,
                            selected = casting?.id == renderer.id,
                        ) {
                            if (casting?.id == renderer.id) {
                                App.playback.stopCasting()
                            } else {
                                App.playback.castTo(renderer)
                            }
                        }
                    }
                    if (!scanning && renderers.isEmpty()) {
                        item {
                            AppText(
                                "No network speakers found. They need to be on the same Wi-Fi.",
                                nSp(13),
                                lineHeight = nSp(18),
                                dim = true,
                                modifier = Modifier.padding(horizontal = n(20), vertical = n(8)),
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
                            nSp(13),
                            lineHeight = nSp(18),
                            dim = true,
                            modifier = Modifier.padding(horizontal = n(20), vertical = n(12)),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun VolumeFader(volume: Float) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(n(12)),
        ) {
            AppIcon(AppIcons.VolumeDown, size = n(22))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(n(28))
                    .pointerInput(Unit) {
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
            AppIcon(AppIcons.VolumeUp, size = n(22))
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (enabled && onClick != null) Modifier.appClickable(onClick = onClick)
                    else Modifier,
                )
                .padding(horizontal = n(20), vertical = n(10)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(icon, size = n(22), modifier = if (selected) Modifier else Modifier.alpha(0.5f))
            Spacer(Modifier.width(n(14)))
            AppText(label, nSp(17), align = TextAlign.Start, modifier = Modifier.weight(1f), dim = !selected)
            if (selected) LightIcon(LightIcons.ACCEPT, size = 1.4f)
        }
    }
}
