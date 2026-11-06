package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.sublunar.amp.App
import com.sublunar.amp.playback.output.GroupMember
import com.sublunar.amp.playback.output.OutputFamily
import com.sublunar.amp.playback.output.OutputTarget
import com.sublunar.amp.playback.output.VolumeControl
import com.sublunar.amp.playback.output.isSameDeviceAs
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.DISABLED_ALPHA
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.AppProgressBar
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.MENU_ROW_INSET_PX
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.components.horizontalScrub
import com.sublunar.amp.ui.components.listPadding
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.SealedLightActivity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Output and volume, reached from the player.
 *
 * The fader at the top drives the phone's media volume, or the output's while
 * the sound is elsewhere. With two speakers or more playing together (an
 * AirPlay group: hold a speaker to add it, tap a ticked one to take it out)
 * each has its own fader under its row, and the top one is the loudest's:
 * moving it, or the rocker, scales them all. Speaker-vs-Bluetooth routing is LightOS's job (a tool has no access
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
        val groupFlow = active?.group
        val group: List<GroupMember> = key(groupFlow) { groupFlow?.collectAsState()?.value }.orEmpty()
        val families = remember { App.playback.outputFamilies() }
        var found by remember { mutableStateOf<Map<String, List<OutputTarget>>>(emptyMap()) }
        // Families that have answered at least once since the page opened.
        var answered by remember { mutableStateOf(emptySet<String>()) }
        var scanning by remember { mutableStateOf(true) }

        // Discovery is a few seconds of UDP waiting, so it runs once on open and
        // again from the Scan row. Every family listens at once, each shown as
        // it answers, so one network's search doesn't wait behind another's.
        var scanToken by remember { mutableStateOf(0) }
        LaunchedEffect(scanToken, lanUsable) {
            scanning = true
            // A server that went quiet a while ago isn't asked again on its
            // own: a search is a good moment, since what it finds depends on it.
            if (App.source.value.hasServer && !App.rules.value.serverReachable) App.reachability.check(delayMs = 0L)
            coroutineScope {
                for (family in families.searchOrder) {
                    launch {
                        val targets = App.playback.find(family)
                        found = found + (family.id to targets)
                        // An answer given with the network out of reach is no answer.
                        answered = if (lanUsable || !family.needsLan) answered + family.id else answered - family.id
                    }
                }
            }
            scanning = false
        }
        val lanHidden = !lanUsable && families.listOrder.any { it.needsLan }
        val searchable = families.listOrder.any { lanUsable || !it.needsLan }

        // A fader with nothing to move (an Apple TV feeding a receiver reports a
        // volume it doesn't own) is disabled, and dimmed rather than hidden so
        // the page doesn't jump.
        val faderLive = volumeControl != VolumeControl.NotOurs

        ListScreen(onBack = { goBack() }, title = "Output") {
            Column(
                modifier = Modifier
                    .padding(horizontal = px(80), vertical = px(32))
                    .alpha(if (faderLive) 1f else DISABLED_ALPHA),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    AppText("Volume", pxSp(LightType.DETAIL_PX))
                    // A fader with nothing to move says why, where its level isn't.
                    if (!faderLive) AppText(active?.family?.volumeElsewhere.orEmpty(), pxSp(LightType.DETAIL_PX), dim = true)
                }
                Spacer(Modifier.height(px(26)))
                VolumeFader(volume, enabled = faderLive) { App.playback.setVolume(it) }
            }

            Spacer(Modifier.height(px(20)))
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = listPadding()) {
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
                // One row per speaker, whatever has the sound listed at once
                // and once — see OutputFamilies.rows. A family that can't
                // play this source (DLNA with the phone's own files) gives
                // its speakers' rows to one that can, or lists none.
                // One that's handed the server's address needs the server
                // reachable as well. Read here, so the rows follow it.
                val serverUp = rules.serverReachable
                val steerable = { family: OutputFamily ->
                    (lanUsable || !family.needsLan) && (serverUp || !family.needsServer) && App.playback.canSteer(family)
                }
                // A network family's rows wait for the network families listed
                // before it to answer, so a speaker both offer never appears
                // under one and then jumps to the other's place under a finger.
                val settled = found.filterKeys { id ->
                    val before = families.listOrder.takeWhile { it.id != id }
                    families.listOrder.none { it.id == id && it.needsLan } ||
                        before.filter { it.needsLan && steerable(it) }.all { it.id in answered }
                }
                // A grouped speaker is listed whatever this search found.
                val withGroup = active?.takeIf { group.isNotEmpty() }?.let { a ->
                    val listed = settled[a.family.id].orEmpty()
                    settled + (a.family.id to listed + group.map { it.target }.filter { m -> listed.none { it.id == m.id } })
                } ?: settled
                // A grouped speaker another kind also lists (the Denon under DLNA)
                // shows as the group's, so it's ticked and gets its fader.
                val groupFamily = active?.family
                val inGroup = if (groupFamily == null || group.isEmpty()) {
                    withGroup
                } else {
                    withGroup.mapValues { (id, targets) ->
                        if (id == groupFamily.id) targets else targets.filter { t -> group.none { it.target.isSameDeviceAs(t) } }
                    }
                }
                val rows = families.rows(inGroup, active, steerable).filter { (family, _) -> lanUsable || !family.needsLan }
                // A row and its fader are one item, so a speaker leaving a group
                // only changes what that item draws.
                items(rows, key = { (family, target) -> "${family.id}:${target.id}" }) { (family, target) ->
                    val member = group.firstOrNull { it.target.id == target.id }?.takeIf { active?.family === family }
                    val selected = if (group.isNotEmpty()) member != null else active?.family === family && active?.target?.id == target.id
                    Column {
                        OutputRow(
                            // One row stands for a device both families offer (the Denon
                            // under DLNA), so any listing of it that knows it's a speaker counts.
                            icon = if (target.isSpeaker || found.values.any { list -> list.any { it.isSpeaker && it.isSameDeviceAs(target) } }) {
                                AppIcons.Speaker
                            } else {
                                AppIcons.Cast
                            },
                            label = target.name,
                            selected = selected,
                            onLongClick = if (!selected && App.playback.canGroup(family, target, found)) {
                                { App.playback.addToGroup(family, target, found) }
                            } else {
                                null
                            },
                        ) {
                            if (selected) {
                                if (group.isNotEmpty()) App.playback.removeFromGroup(target) else App.playback.playHere()
                            } else if (!App.playback.castTo(family, target)) {
                                // Out of reach after all (the network went, or
                                // nothing can steer it): the row goes, and
                                // the search runs again.
                                found = found + (family.id to found[family.id].orEmpty().filter { it.id != target.id })
                                scanToken++
                            }
                        }
                        if (member != null && group.size > 1) MemberFader(member.level) { App.playback.setMemberVolume(target.id, it) }
                    }
                }
                val onlyUnplayable = rows.isEmpty() &&
                    families.listOrder.any { !steerable(it) && found[it.id].orEmpty().isNotEmpty() }
                if (lanHidden) {
                    item { OutputNote("Network speakers need Wi-Fi.") }
                } else if (!scanning && onlyUnplayable) {
                    // Only a speaker that fetches for itself is ever left out: with
                    // no server to send it to, or one it couldn't reach now.
                    val why = if (App.source.value.hasServer) {
                        "The speakers found can't reach your server right now."
                    } else {
                        "The speakers found can't play music stored on this phone."
                    }
                    item { OutputNote(why) }
                } else if (!scanning && rows.isEmpty()) {
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
                        modifier = Modifier.padding(start = px(MENU_ROW_INSET_PX), top = px(31), bottom = px(31)),
                    )
                }
            }
        }
    }

    /** A grouped speaker's own fader, under its row. */
    @Composable
    private fun MemberFader(level: Float, onMove: (Float) -> Unit) {
        Box(modifier = Modifier.padding(start = px(MENU_ROW_INSET_PX), end = px(80), bottom = px(28))) {
            VolumeFader(level, onMove = onMove)
        }
    }

    @Composable
    private fun VolumeFader(volume: Float, enabled: Boolean = true, onMove: (Float) -> Unit) {
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
                    .horizontalScrub(
                        enabled,
                        onMove = onMove,
                        onEnd = onMove,
                    ),
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
            modifier = Modifier.padding(start = px(MENU_ROW_INSET_PX), top = px(20), bottom = px(20)),
        )
    }

    @Composable
    private fun OutputRow(
        icon: ImageVector,
        label: String,
        selected: Boolean,
        enabled: Boolean = true,
        onLongClick: (() -> Unit)? = null,
        onClick: (() -> Unit)? = null,
    ) {
        TextRow(
            title = label,
            leading = {
                AppIcon(icon, size = px(56), modifier = if (enabled) Modifier else Modifier.alpha(DISABLED_ALPHA))
            },
            checked = selected,
            onClick = if (enabled) onClick else null,
            onLongClick = if (enabled) onLongClick else null,
        )
    }
}
