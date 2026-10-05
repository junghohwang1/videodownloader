package com.example.videodownloader.browser

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.videodownloader.data.db.MediaKind
import com.example.videodownloader.ui.common.TextInputDialog
import com.example.videodownloader.util.Storage
import com.example.videodownloader.util.formatBytes
import com.example.videodownloader.util.formatDuration

/** 다운로드 창에서 고를 수 있는 항목 하나(HLS 화질 또는 감지된 파일). */
data class DownloadOption(
    val key: String,
    val media: DetectedMedia,
    /** 실제로 받을 주소. HLS 화질을 고르면 해당 화질의 플레이리스트 주소다. */
    val url: String,
    val label: String,
    val sizeBytes: Long,
    val sizeEstimated: Boolean,
    val height: Int,
    /** 저장할 파일 확장자. */
    val ext: String,
    /** 유튜브 형식 키. 다른 종류는 null. */
    val formatSpec: String? = null,
)

fun buildDownloadOptions(items: List<DetectedMedia>): List<DownloadOption> =
    items.flatMap { media ->
        if (media.kind == MediaKind.YOUTUBE) {
            media.ytFormats.map { f ->
                DownloadOption(
                    key = media.url + "#" + f.spec,
                    media = media,
                    url = media.url,
                    label = f.label,
                    sizeBytes = f.sizeBytes,
                    sizeEstimated = false,
                    height = f.height,
                    ext = f.ext,
                    formatSpec = f.spec,
                )
            }
        } else if (media.kind == MediaKind.HLS && media.variants.isNotEmpty()) {
            media.variants.map { v ->
                val height = heightOf(v.resolution)
                DownloadOption(
                    key = v.url,
                    media = media,
                    url = v.url,
                    label = qualityLabel(height, v.bandwidth) ?: "HLS",
                    // 대역폭 × 길이로 용량을 어림한다.
                    sizeBytes = if (media.durationSec > 0 && v.bandwidth > 0) (v.bandwidth / 8.0 * media.durationSec).toLong() else -1,
                    sizeEstimated = true,
                    height = height,
                    ext = "ts",
                )
            }
        } else {
            val height = heightOf(media.resolution)
            listOf(
                DownloadOption(
                    key = media.url,
                    media = media,
                    url = media.url,
                    label = qualityLabel(height, 0) ?: formatLabel(media),
                    sizeBytes = media.sizeBytes,
                    sizeEstimated = false,
                    height = height,
                    ext = MediaSniffer.suggestFileName(media).substringAfterLast('.', "mp4"),
                ),
            )
        }
    }
        .distinctBy { it.key }
        .sortedWith(compareByDescending<DownloadOption> { it.height }.thenByDescending { it.sizeBytes })

private fun heightOf(resolution: String?): Int =
    resolution?.substringAfter('x', "")?.toIntOrNull() ?: 0

private fun qualityLabel(height: Int, bandwidth: Long): String? = when {
    height > 0 -> "${height}P"
    bandwidth > 0 -> "${bandwidth / 1000} kbps"
    else -> null
}

private fun formatLabel(media: DetectedMedia): String =
    if (media.kind == MediaKind.HLS) "HLS"
    else MediaSniffer.suggestFileName(media).substringAfterLast('.', "mp4").uppercase()

private fun sizeText(option: DownloadOption): String = when {
    option.sizeBytes > 0 -> (if (option.sizeEstimated) "약 " else "") + formatBytes(option.sizeBytes)
    !option.media.probed -> "확인 중…"
    else -> "용량 알 수 없음"
}

/**
 * 다운로드 창: 네트워크 상태 → 썸네일·제목 → 화질 선택 → 닫기 / 이름 변경 / 다운로드.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(
    items: List<DetectedMedia>,
    pageTitle: String,
    onDismiss: () -> Unit,
    onDownload: (option: DownloadOption, fileName: String) -> Unit,
) {
    val options = buildDownloadOptions(items)
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val selected = options.firstOrNull { it.key == selectedKey } ?: options.firstOrNull()
    var editedName by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }

    // 유튜브는 페이지 제목("YouTube")이 아니라 영상 제목을 쓴다.
    val youtube = items.firstOrNull { it.kind == MediaKind.YOUTUBE }
    val defaultBase = youtube?.pageTitle?.takeIf { it.isNotBlank() }
        ?: pageTitle.takeIf { it.isNotBlank() }
        ?: selected?.let { MediaSniffer.suggestFileName(it.media).substringBeforeLast('.') }
        ?: "video"
    val baseName = editedName ?: defaultBase
    val ext = selected?.ext ?: "mp4"
    val fileName = Storage.sanitizeFileName("$baseName.$ext")
    val duration = selected?.media?.durationSec ?: youtube?.durationSec ?: 0.0
    val thumbnailUrl = selected?.media?.thumbnailUrl ?: youtube?.thumbnailUrl
    // 유튜브 정보를 가져오는 중이거나 실패한 경우 안내한다.
    val youtubeStatus = when {
        youtube == null || youtube.ytFormats.isNotEmpty() -> null
        !youtube.probed -> "유튜브 영상 정보를 가져오는 중…"
        else -> "유튜브 영상 정보를 가져오지 못했습니다: ${youtube.error}"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            NetworkHeader()
            Spacer(Modifier.height(16.dp))

            if (youtubeStatus != null) {
                Text(
                    youtubeStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (youtube?.probed == true) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            if (options.isEmpty() && youtube != null && !youtube.probed) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            } else if (options.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.VideocamOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                    Text("아직 감지된 영상이 없습니다", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "페이지에서 영상을 재생해 보세요.\n스트리밍 전용(blob)이나 DRM 보호 영상은 받을 수 없습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.Top) {
                    Thumbnail(duration, thumbnailUrl)
                    Spacer(Modifier.width(16.dp))
                    Text(
                        fileName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    options.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            row.forEach { option ->
                                OptionCard(
                                    option = option,
                                    selected = option.key == selected?.key,
                                    onClick = { selectedKey = option.key },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SheetAction(Icons.Outlined.Cancel, "닫기", enabled = true, onClick = onDismiss)
                SheetAction(Icons.Outlined.DriveFileRenameOutline, "이름 변경", enabled = selected != null) { renaming = true }
                SheetAction(Icons.Outlined.FileDownload, "다운로드", enabled = selected != null) {
                    selected?.let { onDownload(it, fileName) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (renaming) {
        TextInputDialog(
            title = "이름 변경",
            initial = baseName,
            label = "파일 이름",
            onConfirm = { editedName = it },
            onDismiss = { renaming = false },
        )
    }
}

@Composable
private fun NetworkHeader() {
    val context = LocalContext.current
    val (label, icon) = remember {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "연결 없음" to Icons.Default.WifiOff
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi" to Icons.Default.Wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "모바일 데이터" to Icons.Default.SignalCellularAlt
            else -> "연결됨" to Icons.Default.Wifi
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "네트워크",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Thumbnail(durationSec: Double, imageUrl: String?) {
    Box(
        modifier = Modifier
            .size(width = 132.dp, height = 84.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF7FA8F5)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Movie, null, tint = Color.White, modifier = Modifier.size(36.dp))
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        if (durationSec > 0) {
            Text(
                formatDuration(durationSec),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(topStart = 6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun OptionCard(option: DownloadOption, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
            Text(option.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(sizeText(option), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Icon(Icons.Default.Check, "선택됨", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
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
