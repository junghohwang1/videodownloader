package com.example.videodownloader.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.example.videodownloader.AppContainer
import com.example.videodownloader.appContainer
import com.example.videodownloader.data.db.DownloadEntity
import com.example.videodownloader.data.db.DownloadStatus
import com.example.videodownloader.data.db.MediaKind
import com.example.videodownloader.data.db.progressFraction
import com.example.videodownloader.ui.common.ConfirmDialog
import com.example.videodownloader.ui.common.EmptyState
import com.example.videodownloader.ui.common.TextInputDialog
import com.example.videodownloader.util.formatBytes
import com.example.videodownloader.util.formatDate
import com.example.videodownloader.util.formatSpeed
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class FilesViewModel(private val container: AppContainer) : ViewModel() {
    private val manager = container.downloadManager
    private val library = container.library

    val downloads: StateFlow<List<DownloadEntity>> =
        manager.downloads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val speeds: StateFlow<Map<Long, Long>> = manager.speeds

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun pause(item: DownloadEntity) = manager.pause(item.id)
    fun resume(item: DownloadEntity) = manager.resume(item.id)

    fun delete(item: DownloadEntity) = launchWithMessage(null) { library.delete(item) }
    fun rename(item: DownloadEntity, name: String) = launchWithMessage("이름을 변경했습니다") { library.rename(item, name) }
    fun moveToPrivate(item: DownloadEntity) =
        launchWithMessage("비공개 폴더로 옮겼습니다") { library.setPrivate(item, true) }
    fun exportToGallery(item: DownloadEntity) =
        launchWithMessage("갤러리(VideoDownloader 폴더)에 저장했습니다") { library.exportToGallery(item) }

    private fun launchWithMessage(success: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { success?.let(_messages::tryEmit) }
                .onFailure { _messages.tryEmit("실패: ${it.message}") }
        }
    }
}

/** 진행 중(대기·일시정지·실패 포함) 다운로드 목록. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(vm: FilesViewModel, snackbar: SnackbarHostState, onOpenBrowser: () -> Unit) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val speeds by vm.speeds.collectAsStateWithLifecycle()
    val active = downloads.filter { it.status != DownloadStatus.COMPLETED }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("진행 상태") })
        if (active.isEmpty()) {
            EmptyState(
                Icons.Outlined.CloudDownload,
                "진행 중인 다운로드가 없습니다",
                "브라우저에서 영상을 찾아 다운로드해 보세요.",
            ) { Button(onClick = onOpenBrowser) { Text("브라우저 열기") } }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(active, key = { it.id }) { item ->
                    ActiveDownloadItem(item, speeds[item.id], vm)
                }
            }
        }
    }
}

/** 다운로드가 끝난 파일 목록(비공개 파일 제외). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompletedScreen(vm: FilesViewModel, snackbar: SnackbarHostState) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val completed = downloads.filter { it.status == DownloadStatus.COMPLETED && !it.isPrivate }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("완료") })
        if (completed.isEmpty()) {
            EmptyState(
                Icons.Outlined.VideoLibrary,
                "받은 파일이 없습니다",
                "다운로드가 끝난 파일이 여기에 표시됩니다.",
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(completed, key = { it.id }) { item ->
                    CompletedItem(item, vm)
                }
            }
        }
    }
}

@Composable
private fun ActiveDownloadItem(item: DownloadEntity, speed: Long?, vm: FilesViewModel) {
    var confirmDelete by remember { mutableStateOf(false) }
    val fraction = item.progressFraction()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.isPrivate) Icon(Icons.Default.Lock, "비공개", Modifier.size(16.dp))
            Text(
                if (item.isPrivate) "비공개 다운로드" else item.fileName,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (item.isPrivate) 4.dp else 0.dp),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (item.status) {
                DownloadStatus.RUNNING, DownloadStatus.QUEUED ->
                    IconButton(onClick = { vm.pause(item) }) { Icon(Icons.Default.Pause, "일시정지") }
                DownloadStatus.PAUSED ->
                    IconButton(onClick = { vm.resume(item) }) { Icon(Icons.Default.PlayArrow, "재개") }
                DownloadStatus.FAILED ->
                    IconButton(onClick = { vm.resume(item) }) { Icon(Icons.Default.Refresh, "다시 시도") }
                DownloadStatus.COMPLETED -> Unit
            }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Close, "삭제") }
        }
        when {
            item.status == DownloadStatus.RUNNING && fraction == null ->
                LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> LinearProgressIndicator(progress = { fraction ?: 0f }, modifier = Modifier.fillMaxWidth())
        }
        Text(
            statusText(item, fraction, speed),
            style = MaterialTheme.typography.bodySmall,
            color = if (item.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "다운로드 삭제",
            message = "다운로드를 취소하고 받은 데이터를 삭제할까요?",
            confirmLabel = "삭제",
            onConfirm = { vm.delete(item) },
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun statusText(item: DownloadEntity, fraction: Float?, speed: Long?): String {
    val parts = mutableListOf<String>()
    when (item.status) {
        DownloadStatus.QUEUED -> parts += "대기 중"
        DownloadStatus.PAUSED -> parts += item.error ?: "일시정지됨"
        DownloadStatus.FAILED -> parts += "실패: ${item.error ?: "알 수 없는 오류"}"
        DownloadStatus.RUNNING -> {
            fraction?.let { parts += "${(it * 100).toInt()}%" }
            speed?.let { parts += formatSpeed(it) }
        }
        DownloadStatus.COMPLETED -> Unit
    }
    if (item.kind == MediaKind.HLS && item.segmentsTotal > 0) {
        parts += "조각 ${item.segmentsDone}/${item.segmentsTotal}"
    }
    parts += if (item.totalBytes > 0) "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}"
    else formatBytes(item.downloadedBytes)
    return parts.joinToString(" · ")
}

@Composable
private fun CompletedItem(item: DownloadEntity, vm: FilesViewModel) {
    val context = LocalContext.current
    val library = context.appContainer.library
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    MediaRow(
        item = item,
        onClick = { runCatching { library.open(context, item) } },
        trailing = {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "더보기") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("공유") }, onClick = {
                        menuOpen = false
                        runCatching { library.share(context, item) }
                    })
                    DropdownMenuItem(text = { Text("갤러리로 내보내기") }, onClick = {
                        menuOpen = false
                        vm.exportToGallery(item)
                    })
                    DropdownMenuItem(text = { Text("이름 변경") }, onClick = {
                        menuOpen = false
                        renaming = true
                    })
                    DropdownMenuItem(text = { Text("비공개 폴더로 이동") }, onClick = {
                        menuOpen = false
                        vm.moveToPrivate(item)
                    })
                    DropdownMenuItem(text = { Text("삭제") }, onClick = {
                        menuOpen = false
                        confirmDelete = true
                    })
                }
            }
        },
    )

    if (renaming) {
        TextInputDialog(
            title = "이름 변경",
            initial = item.fileName.substringBeforeLast('.'),
            label = "파일 이름",
            onConfirm = { vm.rename(item, it) },
            onDismiss = { renaming = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "파일 삭제",
            message = "'${item.fileName}'을(를) 삭제할까요?",
            confirmLabel = "삭제",
            onConfirm = { vm.delete(item) },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** 썸네일 + 이름 + 크기/날짜 한 줄. 파일 탭과 비공개 탭이 함께 쓴다. */
@Composable
fun MediaRow(item: DownloadEntity, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 112.dp, height = 63.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Movie, null, tint = MaterialTheme.colorScheme.outline)
            AsyncImage(
                model = File(item.filePath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(item.fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatBytes(item.totalBytes)} · ${formatDate(item.completedAt ?: item.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing()
    }
}
