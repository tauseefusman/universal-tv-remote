package com.example.tvremote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.tvremote.core.*

@Composable
fun TvsScreen(vm: AppViewModel) {
    val saved by vm.tvs.collectAsState()
    val selected by vm.selected.collectAsState()
    val state by vm.connection.collectAsState()
    val features by vm.features.collectAsState()
    val discovered by vm.discovered.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val haptics by vm.haptics.collectAsState()
    var addIp by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<TvDevice?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("My TVs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)) }
        if (saved.isEmpty()) item { Text("No saved TVs. Scan for TVs on this Wi-Fi network or add one by IP address.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(saved, key = { it.id }) { tv ->
            val isSel = tv.id == selected?.id
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                containerColor = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tv.name, style = MaterialTheme.typography.titleMedium)
                            Text("${tv.platform.label} · ${tv.host}", style = MaterialTheme.typography.bodySmall)
                            val detail = listOfNotNull(tv.model, tv.firmware?.let { "fw $it" }).joinToString(" · ")
                            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
                            if (isSel) Text(describe(state), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { renaming = tv }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Edit, "Rename ${tv.name}") }
                        IconButton(onClick = { vm.forgetTv(tv.id) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Delete, "Forget ${tv.name}") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isSel) Button(onClick = { vm.selectTv(tv) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Use this TV") }
                        if (isSel && state == ConnectionState.NeedsPairing || !tv.pairedBefore) Button(onClick = { vm.startPairing(tv) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Pair") }
                        if (isSel) OutlinedButton(onClick = { vm.startPairing(tv) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Re-pair") }
                    }
                }
            }
        }

        // Feature availability for the connected TV
        if (selected != null && state == ConnectionState.Connected) {
            item {
                SectionLabel("What ${selected!!.name} supports")
                Feature.entries.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 32.dp)) {
                        Icon(if (f in features) Icons.Default.Check else Icons.Default.Close, null,
                            tint = if (f in features) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.width(8.dp)); Text(featureLabel(f) + if (f in features) "" else " (not available)")
                    }
                }
                vm.powerOnHint()?.let { Text("Power-on over Wi-Fi: $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
        }

        item { SectionLabel("Find TVs") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.scan() }, enabled = !scanning, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (scanning) "Scanning…" else "Scan Wi-Fi") }
                OutlinedButton(onClick = { addIp = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add by IP") }
                if (scanning) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        items(discovered.filter { d -> saved.none { it.id == d.id } }, key = { "d_" + it.id }) { tv ->
            ListItem(headlineContent = { Text(tv.name) }, supportingContent = { Text("${tv.platform.label} · ${tv.host}") },
                trailingContent = { Button(onClick = { vm.startPairing(tv) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Pair") } })
        }

        item {
            SectionLabel("Settings")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Vibration feedback", Modifier.weight(1f))
                Switch(checked = haptics, onCheckedChange = { vm.setHaptics(it) })
            }
            Text("Everything runs on your local network. No account, no cloud.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (addIp) AddIpDialog({ addIp = false }) { vm.addManual(it); addIp = false }
    renaming?.let { tv -> RenameDialog(tv.name, { renaming = null }) { vm.renameTv(tv.id, it); renaming = null } }
    PairingDialog(vm)
}

private fun describe(s: ConnectionState) = when (s) {
    ConnectionState.Connected -> "Connected"; ConnectionState.Connecting -> "Connecting…"
    ConnectionState.NeedsPairing -> "Needs pairing"; ConnectionState.Sleeping -> "Asleep (on network, control off)"
    ConnectionState.Unreachable -> "Not found on this Wi-Fi"; ConnectionState.Disconnected -> "Disconnected"
    is ConnectionState.Error -> "Error"
}

private fun featureLabel(f: Feature) = when (f) {
    Feature.POWER_TOGGLE -> "Power"; Feature.POWER_ON_NETWORK -> "Power on over Wi-Fi"; Feature.DPAD -> "Direction pad"
    Feature.VOLUME -> "Volume"; Feature.MUTE -> "Mute"; Feature.CHANNEL -> "Channel +/−"; Feature.NUMBERS -> "Number pad"
    Feature.MEDIA -> "Playback controls"; Feature.SETTINGS -> "Settings key"; Feature.SOURCE -> "Input / source key"
    Feature.TEXT_INPUT -> "Keyboard text entry"; Feature.TOUCHPAD -> "Touchpad"; Feature.HOLD_KEYS -> "Press and hold"
    Feature.LAUNCH_APPS -> "Launch apps"; Feature.LIST_APPS -> "List installed apps"; Feature.HDMI_INPUTS -> "Direct HDMI inputs"
}

@Composable
private fun AddIpDialog(dismiss: () -> Unit, add: (String) -> Unit) {
    var ip by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Add TV by IP address") },
        text = { Column {
            Text("Find it in the TV's network settings (for example 192.168.1.42).", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(ip, { ip = it }, singleLine = true, label = { Text("IP address") })
        } },
        confirmButton = { TextButton(onClick = { add(ip) }, enabled = ip.isNotBlank()) { Text("Connect") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun RenameDialog(current: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Rename TV") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name, e.g. Living Room") }) },
        confirmButton = { TextButton(onClick = { save(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun PairingDialog(vm: AppViewModel) {
    val p by vm.pairing.collectAsState()
    if (p is PairingUi.Idle) return
    var code by remember(p::class) { mutableStateOf("") }
    val title: String; val body: String
    when (val s = p) {
        is PairingUi.Starting -> { title = "Contacting ${s.tv.name}…"; body = "Keep the TV on and on the same Wi-Fi as this phone." }
        is PairingUi.AwaitCode -> { title = "Enter the code on ${s.tv.name}"; body = "A 6-character code is showing on the TV screen. Type it below." }
        is PairingUi.AwaitApproval -> { title = "Approve on ${s.tv.name}"; body = "Look at the TV and choose Allow / Yes when asked to accept this device. This can take a few seconds to appear." }
        is PairingUi.Failed -> { title = "Pairing didn't finish"; body = s.message + "\n\nTips: keep the TV awake, stay on the same Wi-Fi, and if you tapped Deny, try again and choose Allow." }
        PairingUi.Idle -> return
    }
    AlertDialog(onDismissRequest = { vm.cancelPairing() }, title = { Text(title) },
        text = { Column {
            Text(body)
            if (p is PairingUi.AwaitCode) OutlinedTextField(code, { code = it.take(6).uppercase() }, singleLine = true, label = { Text("Code") }, modifier = Modifier.padding(top = 12.dp))
            if (p is PairingUi.Starting || p is PairingUi.AwaitApproval) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
        } },
        confirmButton = {
            when (val s = p) {
                is PairingUi.AwaitCode -> TextButton(onClick = { vm.submitCode(code) }, enabled = code.length == 6) { Text("Pair") }
                is PairingUi.Failed -> TextButton(onClick = { vm.startPairing(s.tv) }) { Text("Try again") }
                else -> {}
            }
        },
        dismissButton = { TextButton(onClick = { vm.cancelPairing() }) { Text("Cancel") } })
}
