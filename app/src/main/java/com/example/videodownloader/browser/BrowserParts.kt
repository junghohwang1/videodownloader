package com.example.videodownloader.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.videodownloader.data.db.BookmarkEntity
import com.example.videodownloader.ui.common.ConfirmDialog
import com.example.videodownloader.ui.common.EmptyState
import com.example.videodownloader.util.formatDate

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeContent(
    bookmarks: List<BookmarkEntity>,
    onSearch: (String) -> Unit,
    onOpen: (String) -> Unit,
    onAddBookmark: (String, String) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<BookmarkEntity?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 80.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(24.dp))
                Text("동영상 다운로더", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("검색어 또는 주소 입력") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "웹페이지에서 영상을 재생하면 아래쪽 다운로드 버튼에 감지된 영상 개수가 표시됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(bookmarks, key = { it.id }) { bookmark ->
            SiteTile(
                label = bookmark.title,
                letter = bookmark.title.firstOrNull()?.uppercase() ?: "?",
                modifier = Modifier.combinedClickable(
                    onClick = { onOpen(bookmark.url) },
                    onLongClick = { deleteTarget = bookmark },
                ),
            )
        }
        item {
            SiteTile(label = "추가", icon = true, modifier = Modifier.clickable { showAdd = true })
        }
    }

    if (showAdd) {
        AddBookmarkDialog(onDismiss = { showAdd = false }, onConfirm = onAddBookmark)
    }
    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "북마크 삭제",
            message = "'${target.title}'을(를) 삭제할까요?",
            confirmLabel = "삭제",
            onConfirm = { onDeleteBookmark(target.id) },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun SiteTile(label: String, modifier: Modifier = Modifier, letter: String = "", icon: Boolean = false) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (icon) {
                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            } else {
                Text(letter, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AddBookmarkDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("사이트 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("이름") }, singleLine = true)
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("주소") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.length > "https://".length,
                onClick = {
                    onConfirm(title.trim(), url.trim())
                    onDismiss()
                },
            ) { Text("추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: BrowserViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val history by vm.history.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("방문 기록") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                actions = {
                    if (history.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("전체 삭제") }
                },
            )
        },
    ) { padding ->
        if (history.isEmpty()) {
            Box(Modifier.padding(padding)) {
                EmptyState(Icons.Default.History, "방문 기록이 없습니다", "방문한 페이지가 여기에 표시됩니다.")
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(history, key = { it.id }) { item ->
                    ListItem(
                        modifier = Modifier.clickable { onOpen(item.url) },
                        headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text("${formatDate(item.visitedAt)} · ${item.url}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = {
                            IconButton(onClick = { vm.deleteHistory(item.id) }) { Icon(Icons.Default.Close, "삭제") }
                        },
                    )
                }
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "방문 기록 삭제",
            message = "모든 방문 기록을 삭제할까요?",
            confirmLabel = "삭제",
            onConfirm = vm::clearHistory,
            onDismiss = { confirmClear = false },
        )
    }
}
