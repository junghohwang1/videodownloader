package com.example.videodownloader.browser

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.videodownloader.util.AppShare
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 브라우저 화면: 위쪽 주소창 + 웹페이지 + 페이지 위에 떠 있는 반투명 다운로드 버튼.
 * 탭 목록은 하단 내비게이션의 "탭"을 한 번 더 누르면 열린다([showTabs]).
 */
@Composable
fun BrowserScreen(
    vm: BrowserViewModel,
    tabs: BrowserTabs,
    snackbar: SnackbarHostState,
    showTabs: Boolean,
    onShowTabsChange: (Boolean) -> Unit,
    onOpenProgress: () -> Unit,
    onOpenPrivate: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val currentTabId by vm.currentTabId.collectAsStateWithLifecycle()
    val tabInfos by vm.tabInfos.collectAsStateWithLifecycle()
    val hideFixedBars by vm.hideFixedBars.collectAsStateWithLifecycle()
    val webView = tabs.get(currentTabId)
    val ui by vm.ui.collectAsStateWithLifecycle()
    val detected by vm.detected.collectAsStateWithLifecycle()
    val pendingDownload by vm.pendingDownload.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    var showSheet by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    fun load(input: String) {
        if (input.isBlank()) return
        val url = vm.resolveInput(input)
        vm.showPage()
        webView.loadUrl(url)
    }

    fun goBack() {
        if (ui.canGoBack) webView.goBack() else vm.showHome()
    }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is BrowserEvent.DownloadQueued -> {
                    val result = snackbar.showSnackbar(
                        message = "다운로드를 시작했습니다: ${event.fileName}",
                        actionLabel = "보기",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) onOpenProgress()
                }
                is BrowserEvent.Message -> snackbar.showSnackbar(event.text)
                is BrowserEvent.OfferTranslation -> {
                    val language = event.languageName?.let { "($it) " }.orEmpty()
                    val result = snackbar.showSnackbar(
                        message = "이 페이지${language}를 한국어로 번역할까요?",
                        actionLabel = "번역",
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        tabs.get(event.tabId).loadUrl(PageTranslation.translateUrl(event.url))
                    } else {
                        vm.declineTranslation(event.url)
                    }
                }
            }
        }
    }

    // 동적으로 바뀌는 플레이어·고정 메뉴를 위해 페이지를 보는 동안 주기적으로 다시 살핀다.
    LaunchedEffect(currentTabId, ui.showHome, ui.url, hideFixedBars) {
        if (ui.showHome) return@LaunchedEffect
        while (true) {
            if (hideFixedBars) webView.hideFixedBars()
            delay(3_000)
            webView.scanVideoElements { vm.onVideoElements(currentTabId, it) }
        }
    }

    BackHandler(enabled = showHistory || showTabs || !ui.showHome) {
        when {
            showTabs -> onShowTabsChange(false)
            showHistory -> showHistory = false
            else -> goBack()
        }
    }

    if (showHistory) {
        HistoryScreen(
            vm = vm,
            onBack = { showHistory = false },
            onOpen = { url ->
                showHistory = false
                load(url)
            },
        )
        return
    }

    if (showTabs) {
        TabsScreen(
            tabs = tabInfos,
            currentTabId = currentTabId,
            onSelect = {
                vm.switchTab(it)
                onShowTabsChange(false)
            },
            onClose = vm::closeTab,
            onCloseAll = vm::closeAllTabs,
            onNewTab = {
                vm.newTab()
                onShowTabsChange(false)
            },
            onBack = { onShowTabsChange(false) },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        AddressBar(
            url = if (ui.showHome) "" else ui.url,
            loading = ui.loading,
            showBack = !ui.showHome,
            onBack = ::goBack,
            onSubmit = ::load,
            onReloadOrStop = { if (ui.loading) webView.stopLoading() else webView.reload() },
            menu = { dismiss ->
                if ((ui.canGoForward && !ui.showHome) || (ui.showHome && ui.hasPage)) {
                    DropdownMenuItem(text = { Text("앞으로") }, onClick = {
                        dismiss()
                        if (ui.showHome) vm.showPage() else webView.goForward()
                    })
                }
                if (!ui.showHome) {
                    DropdownMenuItem(text = { Text("홈") }, onClick = {
                        dismiss()
                        vm.showHome()
                    })
                }
                DropdownMenuItem(text = { Text("새 탭") }, onClick = {
                    dismiss()
                    vm.newTab()
                })
                DropdownMenuItem(text = { Text("열린 탭 (${tabInfos.size})") }, onClick = {
                    dismiss()
                    onShowTabsChange(true)
                })
                DropdownMenuItem(text = { Text("방문 기록") }, onClick = {
                    dismiss()
                    showHistory = true
                })
                if (!ui.showHome) {
                    val translated = PageTranslation.isTranslated(ui.url)
                    DropdownMenuItem(text = { Text(if (translated) "원문 보기" else "한국어로 번역") }, onClick = {
                        dismiss()
                        if (translated) PageTranslation.originalUrl(ui.url)?.let(webView::loadUrl)
                        else webView.loadUrl(PageTranslation.translateUrl(ui.url))
                    })
                    DropdownMenuItem(text = { Text("북마크에 추가") }, onClick = {
                        dismiss()
                        vm.currentPageForBookmark()?.let { (title, url) -> vm.addBookmark(title, url) }
                    })
                    DropdownMenuItem(text = { Text("페이지 공유") }, onClick = {
                        dismiss()
                        runCatching { AppShare.sharePage(context, ui.title, ui.url) }
                    })
                    DropdownMenuItem(text = { Text("링크 복사") }, onClick = {
                        dismiss()
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("url", ui.url)))
                        }
                    })
                    DropdownMenuItem(text = { Text("다른 브라우저로 열기") }, onClick = {
                        dismiss()
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ui.url))) }
                    })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("비공개 폴더") }, onClick = {
                    dismiss()
                    onOpenPrivate()
                })
                DropdownMenuItem(text = { Text("친구에게 앱 공유") }, onClick = {
                    dismiss()
                    runCatching { AppShare.shareApp(context) }
                })
                DropdownMenuItem(text = { Text("설정") }, onClick = {
                    dismiss()
                    onOpenSettings()
                })
            },
        )
        if (ui.loading && !ui.showHome) {
            LinearProgressIndicator(
                progress = { ui.progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            // 탭이 바뀌면 그 탭의 WebView 로 갈아 끼운다. 같은 WebView 를 다시 붙이므로 페이지 상태가 유지된다.
            key(currentTabId) {
                AndroidView(
                    factory = {
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (ui.showHome) {
                HomeContent(
                    bookmarks = bookmarks,
                    onSearch = ::load,
                    onOpen = ::load,
                    onAddBookmark = vm::addBookmark,
                    onDeleteBookmark = vm::deleteBookmark,
                )
            } else {
                DownloadFab(
                    count = detected.size,
                    onClick = { showSheet = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        // 유튜브처럼 하단 메뉴를 남겨 두는 사이트에서는 그 메뉴 위로 올린다.
                        .padding(end = 16.dp, bottom = if (keepsFixedBars(ui.url)) 72.dp else 20.dp),
                )
            }
        }
    }

    // 다운로드 버튼(감지된 영상) 또는 웹페이지의 다운로드 링크(pendingDownload)로 연다.
    val sheetItems = pendingDownload?.let { listOf(it) } ?: if (showSheet) detected else null
    if (sheetItems != null) {
        DownloadSheet(
            items = sheetItems,
            pageTitle = ui.title,
            onDismiss = {
                showSheet = false
                vm.dismissPendingDownload()
            },
            onDownload = { option, fileName ->
                showSheet = false
                vm.startDownload(option.media, fileName, isPrivate = false, url = option.url, formatSpec = option.formatSpec)
            },
        )
    }
}

@Composable
private fun AddressBar(
    url: String,
    loading: Boolean,
    showBack: Boolean,
    onBack: () -> Unit,
    onSubmit: (String) -> Unit,
    onReloadOrStop: () -> Unit,
    menu: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var field by remember(url) { mutableStateOf(TextFieldValue(url)) }
    var menuOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            }
            TextField(
                value = field,
                onValueChange = { field = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (showBack) 0.dp else 4.dp)
                    .onFocusChanged { state ->
                        if (state.isFocused) field = field.copy(selection = TextRange(0, field.text.length))
                    },
                placeholder = { Text("검색어 또는 주소 입력") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    focusManager.clearFocus()
                    onSubmit(field.text)
                }),
                trailingIcon = when {
                    field.text.isEmpty() -> null
                    url.isNotEmpty() && field.text == url -> {
                        {
                            IconButton(onClick = onReloadOrStop) {
                                Icon(
                                    if (loading) Icons.Default.Close else Icons.Default.Refresh,
                                    if (loading) "중지" else "새로고침",
                                )
                            }
                        }
                    }
                    else -> {
                        { IconButton(onClick = { field = TextFieldValue("") }) { Icon(Icons.Default.Close, "지우기") } }
                    }
                },
            )
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "메뉴") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menu { menuOpen = false }
                }
            }
        }
    }
}

/**
 * 웹페이지 위 오른쪽 아래에 떠 있는 반투명 원형 다운로드 버튼.
 * 페이지 내용을 가리지 않도록 평소에는 옅게, 영상이 감지되면 진하게 표시하고 개수 배지를 붙인다.
 */
@Composable
private fun DownloadFab(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val active = count > 0
    BadgedBox(
        modifier = modifier,
        badge = {
            if (active) {
                Badge(
                    modifier = Modifier.offset(x = (-8).dp, y = 8.dp),
                    containerColor = MaterialTheme.colorScheme.error,
                ) { Text("$count", style = MaterialTheme.typography.labelLarge) }
            }
        },
    ) {
        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier.size(68.dp),
            shape = CircleShape,
            containerColor = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
            else Color.Gray.copy(alpha = 0.55f),
            contentColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
        ) {
            Icon(Icons.Default.Download, contentDescription = "감지된 영상 다운로드", modifier = Modifier.size(36.dp))
        }
    }
}
