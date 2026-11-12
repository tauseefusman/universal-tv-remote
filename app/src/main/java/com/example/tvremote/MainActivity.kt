package com.example.tvremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.tvremote.ui.AppViewModel
import com.example.tvremote.ui.AppsScreen
import com.example.tvremote.ui.RemoteScreen
import com.example.tvremote.ui.TvsScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface { Root() }
            }
        }
    }

    @Composable
    private fun Root() {
        var tab by remember { mutableIntStateOf(0) }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.SettingsRemote, null) }, label = { Text("Remote") })
                    NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.Apps, null) }, label = { Text("Apps") })
                    NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Tv, null) }, label = { Text("TVs") })
                }
            },
        ) { pad ->
            androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
                when (tab) {
                    0 -> RemoteScreen(vm, goToTvs = { tab = 2 })
                    1 -> AppsScreen(vm)
                    else -> TvsScreen(vm)
                }
            }
        }
    }
}
