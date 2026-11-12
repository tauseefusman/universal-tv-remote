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
import com.example.tvremote.core.ConnectionState
import com.example.tvremote.core.Feature

@Composable
fun AppsScreen(vm: AppViewModel) {
    val tv by vm.selected.collectAsState()
    val state by vm.connection.collectAsState()
    val features by vm.features.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val apps by vm.apps.collectAsState()
    val live = state == ConnectionState.Connected

    LaunchedEffect(tv?.id, live) { if (live && Feature.LIST_APPS in features) vm.refreshApps() }

    if (tv == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Select a TV first.") }; return }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("Apps on ${tv!!.name}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)) }
        if (!live) item { Text("Connect to the TV to launch apps.", color = MaterialTheme.colorScheme.error) }

        item { SectionLabel("Favorites") }
        if (favorites.isEmpty()) item { Text("Pin apps from the list below.", style = MaterialTheme.typography.bodySmall) }
        items(favorites, key = { it }) { entry ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { vm.launchEntry(entry) }, enabled = live && Feature.LAUNCH_APPS in features,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text(vm.entryLabel(entry)) }
                IconButton(onClick = { vm.move(entry, -1) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.ArrowUpward, "Move ${vm.entryLabel(entry)} up") }
                IconButton(onClick = { vm.move(entry, 1) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.ArrowDownward, "Move ${vm.entryLabel(entry)} down") }
                IconButton(onClick = { vm.unpin(entry) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Remove ${vm.entryLabel(entry)} from favorites") }
            }
        }

        item { SectionLabel("Installed apps") }
        when {
            Feature.LIST_APPS !in features -> item {
                Text("This TV doesn't share its app list. Android TV only supports the one-tap shortcuts above.", style = MaterialTheme.typography.bodySmall)
            }
            apps.isEmpty() -> item { Text(if (live) "Loading, or the TV returned no apps…" else "Not connected.", style = MaterialTheme.typography.bodySmall) }
            else -> items(apps, key = { it.id }) { app ->
                val entry = "app:${app.id}|${app.name}"
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.launchEntry(entry) }, modifier = Modifier.weight(1f), enabled = live) { Text(app.name, modifier = Modifier.fillMaxWidth()) }
                    val pinned = favorites.contains(entry)
                    IconButton(onClick = { if (pinned) vm.unpin(entry) else vm.pin(app) }, modifier = Modifier.size(48.dp)) {
                        Icon(if (pinned) Icons.Default.PushPin else Icons.Default.PushPin, if (pinned) "Unpin ${app.name}" else "Pin ${app.name}",
                            tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
