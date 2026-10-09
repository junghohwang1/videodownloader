package com.example.videodownloader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.videodownloader.AppContainer
import com.example.videodownloader.data.DownloadStatus

private enum class Tab(val label: String, val icon: ImageVector) {
    GET("받기", Icons.Filled.Download),
    LIBRARY("완료", Icons.Filled.VideoLibrary),
    SETTINGS("설정", Icons.Filled.Settings),
}

@Composable
fun App(container: AppContainer) {
    AppTheme {
        Surface(Modifier.fillMaxSize()) {
            var tab by rememberSaveable { mutableStateOf(Tab.GET) }
            val items by container.downloads.downloads.collectAsState()
            val activeCount = items.count { it.status.isActive }
            val getState = remember { GetState(container) }

            Row(Modifier.fillMaxSize()) {
                NavigationRail(Modifier.fillMaxHeight()) {
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {
                                BadgedBox(badge = {
                                    if (t == Tab.GET && activeCount > 0) Badge { Text("$activeCount") }
                                }) { Icon(t.icon, contentDescription = t.label) }
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
                VerticalDivider()
                Box(Modifier.fillMaxSize()) {
                    when (tab) {
                        Tab.GET -> GetScreen(container, getState)
                        Tab.LIBRARY -> LibraryScreen(container, items.filter { it.status == DownloadStatus.COMPLETED })
                        Tab.SETTINGS -> SettingsScreen(container)
                    }
                }
            }
        }
    }
}
