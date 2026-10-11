package com.example.videodownloader.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.key
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import com.example.videodownloader.AppContainer
import com.example.videodownloader.browser.Bookmark
import com.example.videodownloader.browser.BrowserController
import com.example.videodownloader.browser.BrowserUi
import com.example.videodownloader.browser.DetectedMedia
import kotlinx.coroutines.flow.MutableStateFlow
import com.example.videodownloader.browser.EngineState
import com.example.videodownloader.download.NewDownload
import com.example.videodownloader.util.DesktopActions
import kotlinx.coroutines.delay
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * 브라우저 화면(Android 판과 같은 구성): 위쪽 주소창 + 웹페이지 + 페이지 위에 떠 있는 반투명 원형 다운로드 버튼.
 * 버튼에는 감지된 영상 개수가 배지로 붙고, 누르면 화질을 고르는 다운로드 창이 열린다.
 */
@Composable
fun BrowserScreen(container: AppContainer, onOpenProgress: () -> Unit) {
    val engine by container.browserEngine.state.collectAsState()
    val tabs = container.browser

    LaunchedEffect(engine) {
        (engine as? EngineState.Ready)?.let { tabs.attach(it.host) }
    }

    when (val state = engine) {
        is EngineState.Preparing -> EngineProgress(state.message)
        is EngineState.Failed -> EngineError(state.message) { container.restartBrowserEngine() }
        is EngineState.Ready -> BrowserContent(container, onOpenProgress)
    }
}

@Composable
private fun BrowserContent(container: AppContainer, onOpenProgress: () -> Unit) {
    val tabs = container.browser
    val tabList by tabs.tabs.collectAsState()
    val vm by tabs.current.collectAsState()
    val ui by (vm?.ui ?: remember { MutableStateFlow(BrowserUi()) }).collectAsState()
    val detected by (vm?.detected ?: remember { MutableStateFlow(emptyList<DetectedMedia>()) }).collectAsState()
    val pending by (vm?.pending ?: remember { MutableStateFlow<DetectedMedia?>(null) }).collectAsState()
    val bookmarks by container.bookmarks.items.collectAsState()
    val current = vm

    var showSheet by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(message) {
        if (message != null) {
            delay(5_000)
            message = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (current != null && !ui.fullscreen) {
            TabStrip(
                tabs = tabList,
                currentId = current.id,
                onSelect = tabs::select,
                onClose = tabs::close,
                onNew = { tabs.newTab() },
            )
            AddressBar(
                url = if (ui.showHome) "" else ui.url,
                loading = ui.loading,
                showBack = !ui.showHome,
                canGoForward = ui.canGoForward || (ui.showHome && ui.url.isNotBlank()),
                onBack = current::goBack,
                onForward = current::goForward,
                onSubmit = current::load,
                onReloadOrStop = current::reloadOrStop,
                menu = { dismiss ->
                    if (!ui.showHome) {
                        DropdownMenuItem(text = { Text("홈") }, onClick = { dismiss(); current.showHome() })
                        DropdownMenuItem(text = { Text("북마크에 추가") }, onClick = {
                            dismiss()
                            current.addCurrentBookmark()
                            message = "북마크에 추가했습니다"
                        })
                        DropdownMenuItem(text = { Text("링크 복사") }, onClick = {
                            dismiss()
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(ui.url), null)
                        })
                        DropdownMenuItem(text = { Text("다른 브라우저로 열기") }, onClick = {
                            dismiss()
                            DesktopActions.browse(ui.url)
                        })
                        HorizontalDivider()
                    }
                    DropdownMenuItem(text = { Text("새 탭") }, onClick = { dismiss(); tabs.newTab() })
                    DropdownMenuItem(text = { Text("진행 상태") }, onClick = { dismiss(); onOpenProgress() })
                    DropdownMenuItem(text = { Text("저장 폴더 열기") }, onClick = {
                        dismiss()
                        DesktopActions.open(container.storage.downloadDir)
                    })
                },
            )
            // 로딩 막대 자리는 늘 비워 둔다. 막대가 생겼다 사라지며 웹페이지 크기가 바뀌면 화면 전체가 다시 그려진다.
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (ui.loading && !ui.showHome) LinearProgressIndicator(Modifier.fillMaxSize())
            }
            message?.let { MessageBar(it, onOpenProgress) { message = null } }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // 모든 탭의 웹뷰(Edge WebView2)가 이 자리 안에 들어간다. 홈 화면이나 탭을 여는 중에도 구성에서 빼지 않고
            // 크기만 0으로 줄인다(빼면 창 핸들이 바뀌어 웹뷰가 사라진다).
            val showPage = current != null && !ui.showHome
            SwingPanel(
                factory = { tabs.pageArea },
                modifier = if (showPage) Modifier.fillMaxSize() else Modifier.size(0.dp),
            )
            when {
                current == null -> EngineProgress("브라우저 시작 중…")
                ui.showHome -> HomeContent(
                    bookmarks = bookmarks,
                    onSearch = current::load,
                    onOpen = current::load,
                    onAdd = container.bookmarks::add,
                    onDelete = container.bookmarks::delete,
                )
                !ui.fullscreen -> {
                    // 웹페이지는 OS 창이라 그 위에 그리는 버튼·메뉴는 별도 창(Popup)으로 띄운다.
                    val margin = with(LocalDensity.current) { 28.dp.roundToPx() }
                    Popup(
                        alignment = Alignment.BottomEnd,
                        offset = IntOffset(-margin, -margin),
                        properties = PopupProperties(focusable = false),
                    ) {
                        DownloadFab(count = detected.size, onClick = { showSheet = true })
                    }
                }
            }
        }
    }

    if (current == null) return

    // 다운로드 버튼(감지된 영상) 또는 우클릭 메뉴(pending)로 연다.
    val sheetItems = pending?.let { listOf(it) } ?: if (showSheet) detected else null
    if (sheetItems != null) {
        DownloadSheet(
            items = sheetItems,
            pageTitle = ui.title,
            saveDir = container.storage.downloadDir,
            onDismiss = {
                showSheet = false
                current.dismissPending()
            },
            onDownload = { media, option, fileName ->
                showSheet = false
                current.dismissPending()
                container.downloads.enqueue(
                    NewDownload(
                        url = option.url,
                        kind = option.kind,
                        fileName = fileName,
                        title = media.title ?: ui.title,
                        pageUrl = media.pageUrl,
                        referer = media.referer,
                        formatSpec = option.formatSpec,
                    ),
                )
                message = "다운로드를 시작했습니다: $fileName"
            },
        )
    }
}

/** 크롬처럼 위쪽에 열린 탭을 나열한다. 가운데 버튼(휠) 클릭으로도 닫는다. */
@Composable
private fun TabStrip(
    tabs: List<BrowserController>,
    currentId: Long,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNew: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState())) {
                tabs.forEach { tab ->
                    key(tab.id) {
                        val tabUi by tab.ui.collectAsState()
                        TabChip(
                            title = when {
                                tabUi.showHome -> "새 탭"
                                tabUi.title.isNotBlank() -> tabUi.title
                                else -> tabUi.url
                            },
                            loading = tabUi.loading && !tabUi.showHome,
                            selected = tab.id == currentId,
                            onClick = { onSelect(tab.id) },
                            onClose = { onClose(tab.id) },
                        )
                    }
                }
            }
            IconButton(onClick = onNew, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Add, "새 탭") }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TabChip(title: String, loading: Boolean, selected: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent
    Row(
        Modifier
            .width(200.dp)
            .height(34.dp)
            .background(background, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.button == PointerButton.Tertiary) onClose()
            }
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Filled.Close, "탭 닫기", modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun AddressBar(
    url: String,
    loading: Boolean,
    showBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onSubmit: (String) -> Unit,
    onReloadOrStop: () -> Unit,
    menu: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var field by remember(url) { mutableStateOf(TextFieldValue(url)) }
    var menuOpen by remember { mutableStateOf(false) }

    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, enabled = showBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            IconButton(onClick = onForward, enabled = canGoForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "앞으로") }
            if (url.isNotEmpty()) {
                IconButton(onClick = onReloadOrStop) {
                    Icon(if (loading) Icons.Filled.Close else Icons.Filled.Refresh, if (loading) "중지" else "새로고침")
                }
            }
            TextField(
                value = field,
                onValueChange = { field = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .onFocusChanged { state ->
                        if (state.isFocused) field = field.copy(selection = TextRange(0, field.text.length))
                    }
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) {
                            onSubmit(field.text); true
                        } else {
                            false
                        }
                    },
                placeholder = { Text("검색어 또는 주소 입력") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSubmit(field.text) }),
                trailingIcon = if (field.text.isNotEmpty() && field.text != url) {
                    { IconButton(onClick = { field = TextFieldValue("") }) { Icon(Icons.Filled.Close, "지우기") } }
                } else {
                    null
                },
            )
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "메뉴") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menu { menuOpen = false }
                }
            }
        }
    }
}

@Composable
private fun MessageBar(text: String, onOpenProgress: () -> Unit, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { onClose(); onOpenProgress() }) { Text("보기", color = MaterialTheme.colorScheme.inversePrimary) }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "닫기") }
        }
    }
}

/**
 * 웹페이지 위 오른쪽 아래에 떠 있는 반투명 원형 다운로드 버튼.
 * 페이지 내용을 가리지 않도록 평소에는 옅게, 영상이 감지되면 진하게 표시하고 개수 배지를 붙인다.
 */
@Composable
private fun DownloadFab(count: Int, onClick: () -> Unit) {
    val active = count > 0
    BadgedBox(
        modifier = Modifier.padding(top = 10.dp, end = 10.dp),
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
            containerColor = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else Color.Gray.copy(alpha = 0.6f),
            contentColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
        ) {
            Icon(Icons.Filled.Download, contentDescription = "감지된 영상 다운로드", modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
private fun HomeContent(
    bookmarks: List<Bookmark>,
    onSearch: (String) -> Unit,
    onOpen: (String) -> Unit,
    onAdd: (String, String) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(40.dp))
                    Text("동영상 다운로더", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) {
                                onSearch(query); true
                            } else {
                                false
                            }
                        },
                        placeholder = { Text("검색어 또는 주소 입력") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "웹페이지에서 영상을 열면 오른쪽 아래 다운로드 버튼에 감지된 영상 개수가 표시됩니다. 영상 링크를 우클릭해 바로 받을 수도 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
            items(bookmarks, key = { it.id }) { bookmark ->
                ContextMenuArea(items = { listOf(ContextMenuItem("삭제") { onDelete(bookmark.id) }) }) {
                    SiteTile(
                        label = bookmark.title,
                        letter = bookmark.title.firstOrNull()?.uppercase() ?: "?",
                        modifier = Modifier.clickable { onOpen(bookmark.url) },
                    )
                }
            }
            item { SiteTile(label = "추가", icon = true, modifier = Modifier.clickable { showAdd = true }) }
        }
    }

    if (showAdd) {
        var title by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("북마크 추가") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("이름") }, singleLine = true)
                    OutlinedTextField(url, { url = it }, label = { Text("주소") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = url.isNotBlank(), onClick = {
                    val full = if (url.startsWith("http")) url.trim() else "https://${url.trim()}"
                    onAdd(title.ifBlank { full }, full)
                    showAdd = false
                }) { Text("추가") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun SiteTile(label: String, modifier: Modifier = Modifier, letter: String = "", icon: Boolean = false) {
    Column(modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (icon) {
                Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            } else {
                Text(letter, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EngineProgress(message: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(message)
    }
}

@Composable
private fun EngineError(message: String, onRetry: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("다시 시도") }
        }
    }
}
