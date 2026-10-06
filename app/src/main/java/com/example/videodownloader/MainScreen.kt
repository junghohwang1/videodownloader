package com.example.videodownloader

import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterNone
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.videodownloader.browser.BrowserScreen
import com.example.videodownloader.browser.BrowserTabs
import com.example.videodownloader.browser.BrowserViewModel
import com.example.videodownloader.data.db.DownloadStatus
import com.example.videodownloader.files.CompletedScreen
import com.example.videodownloader.files.FilesViewModel
import com.example.videodownloader.files.ProgressScreen
import com.example.videodownloader.settings.SettingsScreen
import com.example.videodownloader.settings.SettingsViewModel
import com.example.videodownloader.ui.common.appViewModel
import com.example.videodownloader.vault.PrivateScreen
import com.example.videodownloader.vault.PrivateViewModel

/**
 * 화면 목적지. 하단 바에는 원본 앱처럼 탭·진행 상태·완료 세 개만 두고,
 * 비공개 폴더와 설정은 브라우저 메뉴에서 연다.
 */
enum class MainTab(val label: String, val icon: ImageVector?, val inBottomBar: Boolean) {
    BROWSER("탭", Icons.Outlined.FilterNone, true),
    PROGRESS("진행 상태", Icons.Outlined.Download, true),
    COMPLETED("완료", Icons.Outlined.Folder, true),
    PRIVATE("비공개 폴더", null, false),
    SETTINGS("설정", null, false),
}

private class FullscreenView(val view: View, val callback: WebChromeClient.CustomViewCallback)

@Composable
fun MainScreen(
    pendingUrl: String?,
    onPendingUrlConsumed: () -> Unit,
    requestedTab: MainTab?,
    onRequestedTabConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val browserVm = appViewModel { BrowserViewModel(it) }
    val filesVm = appViewModel { FilesViewModel(it) }
    var tab by rememberSaveable { mutableStateOf(MainTab.BROWSER) }
    var showTabList by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var fullscreen by remember { mutableStateOf<FullscreenView?>(null) }

    // 브라우저 탭별 WebView 는 하단 탭을 오가도 살아 있도록 Activity 범위 컴포지션에서 관리한다.
    val browserTabs = remember {
        BrowserTabs(
            context = context,
            vm = browserVm,
            store = context.appContainer.tabStore,
            scope = context.appContainer.appScope,
            onEnterFullscreen = { view, callback -> fullscreen = FullscreenView(view, callback) },
            onExitFullscreen = { fullscreen = null },
        )
    }
    DisposableEffect(browserTabs) { onDispose { browserTabs.destroyAll() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        browserVm.saveTabsNow()
        browserTabs.saveStates()
    }

    val tabIds by browserVm.tabIds.collectAsStateWithLifecycle()
    val currentTabId by browserVm.currentTabId.collectAsStateWithLifecycle()
    LaunchedEffect(tabIds) { browserTabs.sync(tabIds) }
    LaunchedEffect(currentTabId, tab) {
        // 브라우저를 보고 있지 않으면 모든 탭을 일시정지한다.
        browserTabs.setActive(if (tab == MainTab.BROWSER) currentTabId else -1L)
    }

    // 다른 앱에서 공유한 링크는 새 탭으로 연다(탭이 가득 차면 현재 탭).
    LaunchedEffect(pendingUrl) {
        val url = pendingUrl ?: return@LaunchedEffect
        tab = MainTab.BROWSER
        showTabList = false
        val tabId = browserVm.newTab(showHome = false) ?: browserVm.currentTabId.value.also { browserVm.showPage() }
        browserTabs.get(tabId).loadUrl(url)
        onPendingUrlConsumed()
    }
    LaunchedEffect(requestedTab) {
        requestedTab?.let {
            tab = it
            onRequestedTabConsumed()
        }
    }

    val downloads by filesVm.downloads.collectAsStateWithLifecycle()
    val activeCount = downloads.count { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }

    // "완료" 배지는 마지막으로 완료 화면을 본 뒤 새로 끝난 개수.
    var completedSeenAt by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    if (tab == MainTab.COMPLETED) completedSeenAt = System.currentTimeMillis()
    val newCompleted = downloads.count {
        it.status == DownloadStatus.COMPLETED && !it.isPrivate && (it.completedAt ?: 0) > completedSeenAt
    }
    val tabCount by browserVm.tabInfos.collectAsStateWithLifecycle()

    // 브라우저가 아닌 화면에서 뒤로 가면 브라우저로 돌아간다.
    BackHandler(enabled = tab != MainTab.BROWSER) {
        tab = MainTab.BROWSER
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                CompactBottomBar {
                    MainTab.entries.filter { it.inBottomBar }.forEach { item ->
                        val badge = when (item) {
                            MainTab.BROWSER -> tabCount.size.takeIf { it > 1 }
                            MainTab.PROGRESS -> activeCount.takeIf { it > 0 }
                            MainTab.COMPLETED -> newCompleted.takeIf { it > 0 }
                            else -> null
                        }
                        CompactBottomItem(
                            label = item.label,
                            icon = item.icon ?: Icons.Outlined.Folder,
                            badge = badge,
                            selected = tab == item,
                            onClick = {
                                // 브라우저를 보고 있을 때 "탭"을 한 번 더 누르면 열린 탭 목록을 연다.
                                if (item == MainTab.BROWSER && tab == MainTab.BROWSER) showTabList = !showTabList
                                else if (item == MainTab.BROWSER) showTabList = false
                                tab = item
                            },
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding),
            ) {
                when (tab) {
                    MainTab.BROWSER -> BrowserScreen(
                        vm = browserVm,
                        tabs = browserTabs,
                        snackbar = snackbar,
                        showTabs = showTabList,
                        onShowTabsChange = { showTabList = it },
                        onOpenProgress = { tab = MainTab.PROGRESS },
                        onOpenPrivate = { tab = MainTab.PRIVATE },
                        onOpenSettings = { tab = MainTab.SETTINGS },
                    )
                    MainTab.PROGRESS -> ProgressScreen(filesVm, snackbar, onOpenBrowser = { tab = MainTab.BROWSER })
                    MainTab.COMPLETED -> CompletedScreen(filesVm, snackbar)
                    MainTab.PRIVATE -> PrivateScreen(
                        appViewModel { PrivateViewModel(it) },
                        snackbar,
                        onBack = { tab = MainTab.BROWSER },
                    )
                    MainTab.SETTINGS -> SettingsScreen(
                        appViewModel { SettingsViewModel(it) },
                        snackbar,
                        onBack = { tab = MainTab.BROWSER },
                    )
                }
            }
        }

        // 웹 영상의 전체 화면 재생
        fullscreen?.let { fs ->
            BackHandler {
                fs.callback.onCustomViewHidden()
                fullscreen = null
            }
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        (fs.view.parent as? ViewGroup)?.removeView(fs.view)
                        addView(fs.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Material3 NavigationBar(80dp)의 절반 높이로 줄인 하단 바. */
@Composable
private fun CompactBottomBar(content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(44.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            content = content,
        )
    }
}

@Composable
private fun RowScope.CompactBottomItem(
    label: String,
    icon: ImageVector,
    badge: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val iconContent = @Composable { Icon(icon, null, tint = color, modifier = Modifier.size(20.dp)) }
        if (badge != null) {
            BadgedBox(badge = { Badge { Text("$badge") } }) { iconContent() }
        } else {
            iconContent()
        }
        Text(label, color = color, fontSize = 10.sp, lineHeight = 12.sp)
    }
}
