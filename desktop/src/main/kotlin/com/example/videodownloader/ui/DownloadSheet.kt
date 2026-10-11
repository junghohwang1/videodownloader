package com.example.videodownloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.example.videodownloader.browser.DetectedMedia
import com.example.videodownloader.data.MediaKind
import com.example.videodownloader.download.DownloadOption
import com.example.videodownloader.util.DesktopActions
import com.example.videodownloader.util.Storage
import com.example.videodownloader.util.formatBytes
import java.io.File

private data class SheetOption(val key: String, val media: DetectedMedia, val option: DownloadOption)

private fun buildOptions(items: List<DetectedMedia>): List<SheetOption> =
    items.flatMap { media -> media.options.map { SheetOption(it.url + "#" + it.formatSpec, media, it) } }
        .distinctBy { it.key }
        .sortedWith(compareByDescending<SheetOption> { it.option.height }.thenByDescending { it.option.sizeBytes })

private fun sizeText(o: SheetOption): String = when {
    o.option.sizeBytes > 0 -> (if (o.option.kind == MediaKind.HLS) "약 " else "") + formatBytes(o.option.sizeBytes)
    else -> "용량 알 수 없음"
}

/**
 * 다운로드 창(Android 판의 하단 시트와 같은 구성): 썸네일·파일 이름 → 화질 카드 → 닫기 / 이름 변경 / 다운로드.
 * 웹페이지(Chromium) 위에 떠야 하므로 별도 창으로 연다.
 */
@Composable
fun DownloadSheet(
    items: List<DetectedMedia>,
    pageTitle: String,
    saveDir: File,
    onDismiss: () -> Unit,
    onDownload: (media: DetectedMedia, option: DownloadOption, fileName: String) -> Unit,
) {
    val options = buildOptions(items)
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val selected = options.firstOrNull { it.key == selectedKey } ?: options.firstOrNull()
    var editedName by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }

    // 유튜브는 페이지 제목("YouTube")이 아니라 영상 제목을 쓴다.
    val youtube = items.firstOrNull { it.kind == MediaKind.YOUTUBE }
    val defaultBase = youtube?.title?.takeIf { it.isNotBlank() }
        ?: selected?.media?.title?.takeIf { it.isNotBlank() && selected.media.kind != MediaKind.FILE }
        ?: pageTitle.takeIf { it.isNotBlank() }
        ?: selected?.media?.title
        ?: "video"
    val baseName = editedName ?: defaultBase
    val ext = selected?.option?.ext ?: "mp4"
    val fileName = Storage.sanitizeFileName("$baseName.$ext")
    val duration = selected?.media?.durationSec ?: youtube?.durationSec ?: 0.0
    val thumbnailUrl = selected?.media?.thumbnailUrl ?: youtube?.thumbnailUrl
    val loading = options.isEmpty() && items.any { !it.probed }
    val failure = items.firstNotNullOfOrNull { it.error }?.takeIf { options.isEmpty() }

    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(position = WindowPosition(Alignment.Center), width = 560.dp, height = 600.dp),
        title = "다운로드",
        icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(AppIcon),
        resizable = false,
    ) {
        AppTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                    when {
                        loading -> Column(
                            Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (youtube != null) "유튜브 영상 정보를 가져오는 중…" else "영상 정보를 확인하는 중…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        options.isEmpty() -> Column(
                            Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(Icons.Outlined.VideocamOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                            Text(
                                if (failure != null) "영상 정보를 가져오지 못했습니다" else "아직 감지된 영상이 없습니다",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                failure ?: "페이지에서 영상을 재생해 보세요.\n스트리밍 전용(blob)이나 DRM 보호 영상은 받을 수 없습니다.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }

                        else -> Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Thumbnail(thumbnailUrl, duration, Modifier.width(176.dp).height(99.dp))
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        fileName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    // 어디에 저장되는지 보이고, 눌러서 탐색기로 바로 열 수 있게 한다.
                                    Text(
                                        "저장 위치: ${saveDir.absolutePath}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                    TextButton(
                                        onClick = { DesktopActions.open(saveDir.also { it.mkdirs() }) },
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                                    ) {
                                        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("폴더 열기")
                                    }
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                            Column(
                                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                options.chunked(2).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        row.forEach { o ->
                                            OptionCard(
                                                label = o.option.label,
                                                sub = sizeText(o),
                                                selected = o.key == selected?.key,
                                                onClick = { selectedKey = o.key },
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                        if (row.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        SheetAction(Icons.Outlined.Cancel, "닫기", enabled = true, onClick = onDismiss)
                        SheetAction(Icons.Outlined.DriveFileRenameOutline, "이름 변경", enabled = selected != null) { renaming = true }
                        SheetAction(Icons.Outlined.FileDownload, "다운로드", enabled = selected != null) {
                            selected?.let { onDownload(it.media, it.option, fileName) }
                        }
                    }
                }
            }

            if (renaming) {
                var text by remember { mutableStateOf(baseName) }
                AlertDialog(
                    onDismissRequest = { renaming = false },
                    title = { Text("이름 변경") },
                    text = { OutlinedTextField(text, { text = it }, label = { Text("파일 이름") }, singleLine = true) },
                    confirmButton = {
                        TextButton(enabled = text.isNotBlank(), onClick = {
                            editedName = text.trim()
                            renaming = false
                        }) { Text("확인") }
                    },
                    dismissButton = { TextButton(onClick = { renaming = false }) { Text("취소") } },
                )
            }
        }
    }
}

@Composable
private fun OptionCard(label: String, sub: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier.padding(top = 6.dp, end = 6.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(10.dp),
                )
                .clickable(onClick = onClick)
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-6).dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, "선택됨", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}
