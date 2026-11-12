package com.example.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.tvremote.core.*

@Composable
fun RemoteScreen(vm: AppViewModel, goToTvs: () -> Unit) {
    val tvs by vm.tvs.collectAsState()
    val selected by vm.selected.collectAsState()
    val state by vm.connection.collectAsState()
    val features by vm.features.collectAsState()
    val haptics by vm.haptics.collectAsState()
    var more by remember { mutableStateOf(false) }
    val live = state == ConnectionState.Connected

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TvSwitcher(vm, tvs, selected)
        StatusBanner(vm, state, selected, goToTvs)
        if (selected == null) {
            Spacer(Modifier.height(48.dp))
            Text("No TV yet", style = MaterialTheme.typography.headlineSmall)
            Text("Add your first TV from the TVs tab.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = goToTvs, modifier = Modifier.padding(top = 16.dp).heightIn(min = 56.dp)) { Text("Find my TV") }
            return@Column
        }
        fun on(f: Feature) = live && f in features

        // Top row: power / source / more
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            RemoteButton(Icons.Default.PowerSettingsNew, description = "Power", haptics = haptics,
                enabled = (live && Feature.POWER_TOGGLE in features) || state == ConnectionState.Sleeping || state == ConnectionState.Unreachable,
                onClick = { vm.press(RemoteKey.POWER) })
            RemoteButton(Icons.Default.Input, description = "Input or source", haptics = haptics, enabled = on(Feature.SOURCE), onClick = { vm.press(RemoteKey.SOURCE) })
            RemoteButton(Icons.Default.MoreHoriz, description = "More controls", haptics = haptics, onClick = { more = true })
        }

        Spacer(Modifier.height(12.dp))
        QuickApps(vm, enabled = live && Feature.LAUNCH_APPS in features)
        Spacer(Modifier.weight(1f))

        // D-pad (thumb zone)
        Dpad(vm, haptics, live && Feature.DPAD in features, Feature.HOLD_KEYS in features)

        Spacer(Modifier.height(16.dp))
        // Volume | Mute/Home/Back | Channel
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HoldKey(vm, RemoteKey.VOL_UP, Icons.Default.Add, "Volume up", haptics, on(Feature.VOLUME), round = false)
                Text("VOL", style = MaterialTheme.typography.labelSmall)
                HoldKey(vm, RemoteKey.VOL_DOWN, Icons.Default.Remove, "Volume down", haptics, on(Feature.VOLUME), round = false)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RemoteButton(Icons.Default.VolumeOff, description = "Mute", haptics = haptics, enabled = on(Feature.MUTE), onClick = { vm.press(RemoteKey.MUTE) })
                RemoteButton(Icons.Default.Home, description = "Home", haptics = haptics, enabled = live, onClick = { vm.press(RemoteKey.HOME) })
                RemoteButton(Icons.AutoMirrored.Filled.ArrowBack, description = "Back", haptics = haptics, enabled = live, onClick = { vm.press(RemoteKey.BACK) })
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HoldKey(vm, RemoteKey.CH_UP, Icons.Default.KeyboardArrowUp, "Channel up", haptics, on(Feature.CHANNEL), round = false)
                Text("CH", style = MaterialTheme.typography.labelSmall)
                HoldKey(vm, RemoteKey.CH_DOWN, Icons.Default.KeyboardArrowDown, "Channel down", haptics, on(Feature.CHANNEL), round = false)
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (more) MoreSheet(vm, haptics, features, live) { more = false }
}

@Composable
private fun HoldKey(vm: AppViewModel, key: RemoteKey, icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, haptics: Boolean, enabled: Boolean, round: Boolean = true) =
    RemoteButton(icon, description = desc, haptics = haptics, enabled = enabled, round = round, size = 72.dp,
        onHoldStart = { vm.holdStart(key) }, onHoldEnd = { vm.holdEnd(key) })

@Composable
private fun Dpad(vm: AppViewModel, haptics: Boolean, enabled: Boolean, @Suppress("UNUSED_PARAMETER") nativeHold: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HoldKey(vm, RemoteKey.UP, Icons.Default.KeyboardArrowUp, "Up", haptics, enabled)
        Row(verticalAlignment = Alignment.CenterVertically) {
            HoldKey(vm, RemoteKey.LEFT, Icons.Default.KeyboardArrowLeft, "Left", haptics, enabled)
            RemoteButton(text = "OK", description = "OK, select", haptics = haptics, enabled = enabled, size = 88.dp, onClick = { vm.press(RemoteKey.OK) },
                modifier = Modifier.padding(6.dp))
            HoldKey(vm, RemoteKey.RIGHT, Icons.Default.KeyboardArrowRight, "Right", haptics, enabled)
        }
        HoldKey(vm, RemoteKey.DOWN, Icons.Default.KeyboardArrowDown, "Down", haptics, enabled)
    }
}

@Composable
private fun TvSwitcher(vm: AppViewModel, tvs: List<TvDevice>, selected: TvDevice?) {
    Row(Modifier.fillMaxWidth().horizontalScrollCompat().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tvs.forEach { tv ->
            FilterChip(selected = tv.id == selected?.id, onClick = { vm.selectTv(tv) },
                label = { Text(tv.name) }, leadingIcon = { Icon(Icons.Default.Tv, null) },
                modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))

@Composable
private fun StatusBanner(vm: AppViewModel, state: ConnectionState, tv: TvDevice?, goToTvs: () -> Unit) {
    if (tv == null) return
    val (text, tips) = when (state) {
        ConnectionState.Connected -> return
        ConnectionState.Connecting -> "Connecting to ${tv.name}…" to null
        ConnectionState.NeedsPairing -> "${tv.name} needs to be paired." to "Open the TVs tab and tap Pair."
        ConnectionState.Sleeping -> "${tv.name} looks asleep." to (vm.powerOnHint()?.let { "Tap Power to try waking it. $it" } ?: "Turn it on with the physical remote.")
        ConnectionState.Unreachable -> "Can't see ${tv.name} on this Wi-Fi." to "Check the TV is on, both devices are on the same network, and no VPN or guest Wi-Fi isolation is active. Retrying automatically."
        ConnectionState.Disconnected -> "Not connected." to "Retrying automatically."
        is ConnectionState.Error -> "Connection problem." to state.message
    }
    Card(Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = "Connection status: $text" }) {
        Column(Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.titleSmall)
            tips?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state == ConnectionState.NeedsPairing) TextButton(onClick = goToTvs) { Text("Pair now") }
            if (state == ConnectionState.Sleeping) TextButton(onClick = { vm.wake() }) { Text("Wake TV") }
        }
    }
}

@Composable
private fun QuickApps(vm: AppViewModel, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        KnownApp.entries.forEach { app ->
            FilledTonalButton(onClick = { vm.launch(app) }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 2.dp)) {
                Text(app.label.removeSuffix(" Video"), maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreSheet(vm: AppViewModel, haptics: Boolean, features: Set<Feature>, live: Boolean, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            fun on(f: Feature) = live && f in features

            SectionLabel("Menus")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RemoteButton(Icons.Default.Menu, description = "Menu", haptics = haptics, enabled = live, onClick = { vm.press(RemoteKey.MENU) })
                RemoteButton(Icons.Default.Settings, description = "Settings", haptics = haptics, enabled = on(Feature.SETTINGS), onClick = { vm.press(RemoteKey.SETTINGS) })
            }

            SectionLabel("Playback")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RemoteButton(Icons.Default.SkipPrevious, description = "Previous", haptics = haptics, enabled = on(Feature.MEDIA), onClick = { vm.press(RemoteKey.PREVIOUS) })
                RemoteButton(Icons.Default.FastRewind, description = "Rewind", haptics = haptics, enabled = on(Feature.MEDIA), onClick = { vm.press(RemoteKey.REWIND) })
                RemoteButton(Icons.Default.PlayArrow, description = "Play or pause", haptics = haptics, enabled = on(Feature.MEDIA), onClick = { vm.press(RemoteKey.PLAY_PAUSE) })
                RemoteButton(Icons.Default.FastForward, description = "Fast forward", haptics = haptics, enabled = on(Feature.MEDIA), onClick = { vm.press(RemoteKey.FAST_FORWARD) })
                RemoteButton(Icons.Default.SkipNext, description = "Next", haptics = haptics, enabled = on(Feature.MEDIA), onClick = { vm.press(RemoteKey.NEXT) })
            }

            SectionLabel("Number pad")
            val rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(-1, 0, -1))
            rows.forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    r.forEach { n ->
                        if (n < 0) Spacer(Modifier.size(64.dp))
                        else RemoteButton(text = "$n", description = "Number $n", haptics = haptics, enabled = on(Feature.NUMBERS),
                            onClick = { vm.press(RemoteKey.valueOf("NUM$n")) })
                    }
                }
            }

            SectionLabel("HDMI inputs")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HdmiInput.entries.forEach { i ->
                    OutlinedButton(onClick = { vm.switchInput(i) }, enabled = on(Feature.HDMI_INPUTS), modifier = Modifier.heightIn(min = 48.dp)) { Text(i.label.replace("HDMI ", "")) }
                }
            }
            if (!on(Feature.HDMI_INPUTS)) Text("Direct HDMI shortcuts aren't available on this TV. Use the Source button to cycle.", style = MaterialTheme.typography.bodySmall)

            SectionLabel("Touchpad")
            Touchpad(vm, enabled = on(Feature.TOUCHPAD) || on(Feature.DPAD), tapAsOk = { vm.press(RemoteKey.OK) })

            SectionLabel("Keyboard")
            var text by remember { mutableStateOf("") }
            OutlinedTextField(value = text, onValueChange = { text = it }, enabled = on(Feature.TEXT_INPUT), label = { Text("Type to TV search / text field") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { vm.sendText(text); text = "" }, enabled = on(Feature.TEXT_INPUT) && text.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Send to TV") }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Touchpad(vm: AppViewModel, enabled: Boolean, tapAsOk: () -> Unit) {
    var acc by remember { mutableStateOf(0f to 0f) }
    Box(
        Modifier.fillMaxWidth().height(160.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
            .semantics { contentDescription = "Touchpad. Swipe to move, tap to select." }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(onDragEnd = { acc = 0f to 0f }) { change, drag ->
                    change.consume()
                    var (x, y) = acc; x += drag.x; y += drag.y
                    val step = 70f // pixels per step: one swipe step = one D-pad press (or a pointer move on webOS)
                    if (kotlin.math.abs(x) > step || kotlin.math.abs(y) > step) { vm.swipe(x.toInt(), y.toInt()); x = 0f; y = 0f }
                    acc = x to y
                }
            }
            .pointerInput(enabled) { if (enabled) detectTapGestures(onTap = { tapAsOk() }) },
        contentAlignment = Alignment.Center,
    ) { Text("Swipe · tap to select", color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
