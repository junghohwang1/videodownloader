package com.example.videodownloader.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.videodownloader.AppContainer
import com.example.videodownloader.data.DownloadEntity
import com.example.videodownloader.util.DesktopActions
import com.example.videodownloader.util.formatBytes
import com.example.videodownloader.util.formatDate
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun LibraryScreen(container: AppContainer, completed: List<DownloadEntity>) {
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<DownloadEntity?>(null) }
    var deleting by remember { mutableStateOf<DownloadEntity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val shown = completed
        .filter { query.isBlank() || it.fileName.contains(query, ignoreCase = true) || it.title.contains(query, ignoreCase = true) }
        .sortedByDescending { it.completedAt ?: it.createdAt }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("완료된 파일 ${completed.size}개", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("검색") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                modifier = Modifier.width(280.dp),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { DesktopActions.open(container.storage.downloadDir) }) {
                Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("저장 폴더")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.size(12.dp))
        if (shown.isEmpty()) {
            Text(
                if (completed.isEmpty()) "아직 완료된 다운로드가 없습니다." else "검색 결과가 없습니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(shown, key = { it.id }) { item ->
                val file = File(item.filePath)
                ContextMenuArea(items = {
                    listOf(
                        ContextMenuItem("열기") { open(file) { error = it } },
                        ContextMenuItem("폴더에서 보기") { DesktopActions.showInFolder(file) },
                        ContextMenuItem("이름 바꾸기") { renaming = item },
                        ContextMenuItem("원본 주소 열기") { DesktopActions.browse(item.pageUrl ?: item.url) },
                        ContextMenuItem("삭제") { deleting = item },
                    )
                }) {
                    LibraryRow(
                        item = item,
                        exists = file.exists(),
                        onOpen = { open(file) { error = it } },
                        onShow = { DesktopActions.showInFolder(file) },
                        onRename = { renaming = item },
                        onDelete = { deleting = item },
                    )
                }
                HorizontalDivider()
            }
        }
    }

    renaming?.let { item ->
        var name by remember(item.id) { mutableStateOf(item.fileName.substringBeforeLast('.')) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("이름 바꾸기") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    error = container.downloads.rename(item.id, name)
                    renaming = null
                }, enabled = name.isNotBlank()) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("취소") } },
        )
    }

    deleting?.let { item ->
        var deleteFile by remember(item.id) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("삭제") },
            text = {
                Column {
                    Text("‘${item.fileName}’ 을(를) 목록에서 지울까요?")
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { deleteFile = !deleteFile }) {
                        Checkbox(deleteFile, { deleteFile = it })
                        Text("파일도 삭제")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.downloads.delete(item.id, deleteFile) }
                    deleting = null
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } },
        )
    }
}

private fun open(file: File, onError: (String) -> Unit) {
    if (!file.exists()) onError("파일이 없습니다: ${file.path}")
    else if (!DesktopActions.open(file)) onError("이 파일을 열 수 있는 프로그램이 없습니다")
}

@Composable
private fun LibraryRow(
    item: DownloadEntity,
    exists: Boolean,
    onOpen: () -> Unit,
    onShow: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val isAudio = item.mimeType?.startsWith("audio/") == true || item.fileName.endsWith(".m4a", true)
        Icon(
            if (isAudio) Icons.Filled.AudioFile else Icons.Filled.Movie,
            null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f)) {
            Text(item.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    formatBytes(item.totalBytes),
                    item.completedAt?.let(::formatDate),
                    if (!exists) "파일 없음" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (exists) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        IconButton(onClick = onOpen, enabled = exists) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "열기") }
        IconButton(onClick = onShow) { Icon(Icons.Filled.FolderOpen, "폴더에서 보기") }
        IconButton(onClick = onRename, enabled = exists) { Icon(Icons.Filled.Edit, "이름 바꾸기") }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "삭제") }
    }
}
