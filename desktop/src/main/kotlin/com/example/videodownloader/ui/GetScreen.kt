package com.example.videodownloader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.videodownloader.AppContainer
import com.example.videodownloader.data.DownloadEntity
import com.example.videodownloader.data.DownloadStatus
import com.example.videodownloader.data.progressFraction
import com.example.videodownloader.download.AnalysisResult
import com.example.videodownloader.download.DownloadOption
import com.example.videodownloader.download.NewDownload
import com.example.videodownloader.download.UrlAnalyzer
import com.example.videodownloader.util.DesktopActions
import com.example.videodownloader.util.Storage
import com.example.videodownloader.util.formatBytes
import com.example.videodownloader.util.formatDuration
import com.example.videodownloader.util.formatSpeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 받기 화면 상태. 탭을 옮겨도 분석 결과가 남도록 화면 밖에서 들고 있는다. */
class GetState(private val container: AppContainer) {
    var input by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<AnalysisResult?>(null)
    var selected by mutableStateOf<DownloadOption?>(null)
    var fileName by mutableStateOf("")
    var message by mutableStateOf<String?>(null)
    private var job: Job? = null

    fun analyze() {
        val url = input.trim().takeIf { it.isNotEmpty() } ?: return
        job?.cancel()
        loading = true
        error = null
        result = null
        message = null
        job = container.scope.launch {
            try {
                val r = container.analyzer.analyze(url)
                result = r
                selected = r.options.firstOrNull()
                fileName = Storage.sanitizeFileName(r.title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
            }
        }
    }

    fun clear() {
        job?.cancel()
        loading = false
        input = ""
        error = null
        result = null
        selected = null
        message = null
    }

    fun download() {
        val r = result ?: return
        val option = selected ?: return
        val base = fileName.ifBlank { r.title }
        container.downloads.enqueue(
            NewDownload(
                url = option.url,
                kind = option.kind,
                fileName = Storage.sanitizeFileName("$base.${option.ext}"),
                title = r.title,
                pageUrl = r.pageUrl,
                referer = r.referer,
                formatSpec = option.formatSpec,
            ),
        )
        message = "‘$base’ 다운로드를 시작했습니다"
        result = null
        selected = null
        input = ""
    }
}

@Composable
fun GetScreen(container: AppContainer, state: GetState) {
    val items by container.downloads.downloads.collectAsState()
    val speeds by container.downloads.speeds.collectAsState()
    val phases by container.downloads.phases.collectAsState()
    val inProgress = items.filter { it.status != DownloadStatus.COMPLETED }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { UrlBar(state) }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        if (state.loading) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("주소를 분석하는 중…")
                }
            }
        }
        state.result?.let { result -> item { AnalysisCard(state, result) } }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Text("진행 중", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (inProgress.isNotEmpty()) {
                    TextButton(onClick = { container.downloads.resumeAll() }) { Text("모두 재개") }
                    TextButton(onClick = { container.downloads.pauseAll() }) { Text("모두 일시정지") }
                }
            }
        }
        if (inProgress.isEmpty()) {
            item {
                Text(
                    "진행 중인 다운로드가 없습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        items(inProgress, key = { it.id }) { item ->
            ProgressRow(container, item, speeds[item.id], phases[item.id])
        }
    }
}

@Composable
private fun UrlBar(state: GetState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("동영상 주소", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.input,
                onValueChange = { state.input = it },
                modifier = Modifier.weight(1f).onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) {
                        state.analyze(); true
                    } else {
                        false
                    }
                },
                singleLine = true,
                placeholder = { Text("유튜브·웹페이지·mp4·m3u8 주소를 붙여넣으세요") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { state.analyze() }),
                trailingIcon = {
                    if (state.input.isNotEmpty()) {
                        IconButton(onClick = { state.clear() }) { Icon(Icons.Filled.Close, "지우기") }
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                DesktopActions.clipboardText()?.trim()?.let {
                    state.input = it
                    if (UrlAnalyzer.normalize(it) != null) state.analyze()
                }
            }) {
                Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("붙여넣기")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { state.analyze() }, enabled = state.input.isNotBlank() && !state.loading) {
                Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("분석")
            }
        }
    }
}

@Composable
private fun AnalysisCard(state: GetState, result: AnalysisResult) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Thumbnail(result.thumbnailUrl, result.durationSec, Modifier.width(220.dp).height(124.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(result.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        result.pageUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (result.durationSec > 0) Text("길이 ${formatDuration(result.durationSec)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("형식 선택", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                result.options.forEach { option ->
                    OptionChip(option, selected = option == state.selected) { state.selected = option }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.fileName,
                    onValueChange = { state.fileName = it },
                    label = { Text("파일 이름") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    suffix = { state.selected?.let { Text(".${it.ext}") } },
                )
                Spacer(Modifier.width(12.dp))
                Button(onClick = { state.download() }, enabled = state.selected != null) {
                    Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("다운로드")
                }
            }
        }
    }
}

@Composable
private fun OptionChip(option: DownloadOption, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) colors.primaryContainer else colors.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = Modifier.width(150.dp).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(option.label, fontWeight = FontWeight.Bold)
            Text(
                listOfNotNull(option.detail, option.sizeBytes.takeIf { it > 0 }?.let { (if (option.kind.name == "HLS") "약 " else "") + formatBytes(it) })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProgressRow(container: AppContainer, item: DownloadEntity, speed: Long?, phase: String?) {
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(
                        statusText(item, speed, phase),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when (item.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED ->
                        IconButton(onClick = { container.downloads.pause(item.id) }) { Icon(Icons.Filled.Pause, "일시정지") }
                    DownloadStatus.PAUSED ->
                        IconButton(onClick = { container.downloads.resume(item.id) }) { Icon(Icons.Filled.PlayArrow, "재개") }
                    DownloadStatus.FAILED ->
                        IconButton(onClick = { container.downloads.resume(item.id) }) { Icon(Icons.Filled.Refresh, "다시 시도") }
                    DownloadStatus.COMPLETED -> Unit
                }
                IconButton(onClick = { scope.launch { container.downloads.delete(item.id) } }) {
                    Icon(Icons.Filled.Delete, "삭제")
                }
            }
            Spacer(Modifier.height(6.dp))
            val fraction = item.progressFraction()
            if (item.status == DownloadStatus.RUNNING && (fraction == null || phase != null)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { fraction ?: 0f }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun statusText(item: DownloadEntity, speed: Long?, phase: String?): String {
    phase?.let { return it }
    val size = buildString {
        if (item.segmentsTotal > 0) append("조각 ${item.segmentsDone}/${item.segmentsTotal} · ")
        append(formatBytes(item.downloadedBytes))
        if (item.totalBytes > 0) append(" / ${formatBytes(item.totalBytes)}")
    }
    return when (item.status) {
        DownloadStatus.QUEUED -> "대기 중"
        DownloadStatus.RUNNING -> {
            val pct = item.progressFraction()?.let { " · ${(it * 100).toInt()}%" }.orEmpty()
            val eta = if (speed != null && speed > 0 && item.totalBytes > 0) {
                " · 남은 시간 ${formatDuration((item.totalBytes - item.downloadedBytes).toDouble() / speed)}"
            } else {
                ""
            }
            "$size$pct" + (speed?.let { " · ${formatSpeed(it)}" } ?: "") + eta
        }
        DownloadStatus.PAUSED -> "일시정지 · $size" + (item.error?.let { " · $it" } ?: "")
        DownloadStatus.FAILED -> "실패: ${item.error ?: "알 수 없는 오류"}"
        DownloadStatus.COMPLETED -> "완료"
    }
}
