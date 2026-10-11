package com.example.videodownloader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
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
import com.example.videodownloader.browser.BrowserUi
import com.example.videodownloader.data.DownloadStatus
import kotlinx.coroutines.flow.MutableStateFlow

private enum class Tab(val label: String, val icon: ImageVector) {
    BROWSER("브라우저", Icons.Filled.Language),
    GET("진행", Icons.Filled.Download),
    LIBRARY("완료", Icons.Filled.VideoLibrary),
    SETTINGS("설정", Icons.Filled.Settings),
}

@Composable
fun App(container: AppContainer) {
    AppTheme {
        Surface(Modifier.fillMaxSize()) {
            var tab by rememberSaveable { mutableStateOf(Tab.BROWSER) }
            val items by container.downloads.downloads.collectAsState()
            val currentTab by container.browser.current.collectAsState()
            val browserUi by (currentTab?.ui ?: remember { MutableStateFlow(BrowserUi()) }).collectAsState()
            // 웹 영상 전체 화면일 때는 메뉴를 숨겨 페이지가 창을 가득 채우게 한다.
            val fullscreen = tab == Tab.BROWSER && browserUi.fullscreen
            val activeCount = items.count { it.status.isActive }
            val getState = remember { GetState(container) }

            Row(Modifier.fillMaxSize()) {
                if (!fullscreen) NavigationRail(Modifier.fillMaxHeight()) {
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
                if (!fullscreen) VerticalDivider()
                Box(Modifier.fillMaxSize()) {
                    when (tab) {
                        Tab.BROWSER -> BrowserScreen(container, onOpenProgress = { tab = Tab.GET })
                        Tab.GET -> GetScreen(container, getState)
                        Tab.LIBRARY -> LibraryScreen(container, items.filter { it.status == DownloadStatus.COMPLETED })
                        Tab.SETTINGS -> SettingsScreen(container)
                    }
                }
            }
        }
    }
}
