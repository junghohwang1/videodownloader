package com.example.videodownloader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.videodownloader.AppContainer
import com.example.videodownloader.util.DesktopActions
import com.example.videodownloader.util.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

@Composable
fun SettingsScreen(container: AppContainer) {
    val settings by container.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var ffmpegPath by remember { mutableStateOf(container.ffmpeg.locate()?.path) }
    var ffmpegStatus by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("설정", style = MaterialTheme.typography.titleLarge)

        Section("저장 폴더") {
            Text(container.storage.downloadDir.path, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    chooseDirectory(container.storage.downloadDir.path)?.let { dir ->
                        container.settings.update { it.copy(downloadDir = dir) }
                    }
                }) { Text("변경…") }
                OutlinedButton(onClick = { DesktopActions.open(container.storage.downloadDir) }) { Text("열기") }
                if (settings.downloadDir.isNotBlank()) {
                    TextButton(onClick = { container.settings.update { it.copy(downloadDir = "") } }) { Text("기본값으로") }
                }
            }
        }

        Section("동시 다운로드 수: ${settings.maxConcurrent}") {
            Slider(
                value = settings.maxConcurrent.toFloat(),
                onValueChange = { v -> container.settings.update { it.copy(maxConcurrent = v.toInt().coerceIn(1, 6)) } },
                valueRange = 1f..6f,
                steps = 4,
                modifier = Modifier.width(360.dp),
            )
        }

        SwitchRow("완료되면 알림 표시", settings.notifyOnComplete) { on ->
            container.settings.update { it.copy(notifyOnComplete = on) }
        }
        SwitchRow("창을 닫으면 트레이로 내리기(다운로드 계속)", settings.minimizeToTray) { on ->
            container.settings.update { it.copy(minimizeToTray = on) }
        }

        Section("ffmpeg (유튜브 영상·음성 합치기, HLS → MP4 변환)") {
            Text(
                ffmpegPath ?: "설치되지 않음 — 필요할 때 자동으로 내려받습니다(약 110MB).",
                style = MaterialTheme.typography.bodyMedium,
                color = if (ffmpegPath == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            ffmpegStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (installing) LinearProgressIndicator(Modifier.width(360.dp))
            if (ffmpegPath == null) {
                OutlinedButton(enabled = !installing, onClick = {
                    installing = true
                    scope.launch {
                        try {
                            val exe = container.ffmpeg.ensure { downloaded, total ->
                                ffmpegStatus = "내려받는 중… ${formatBytes(downloaded)} / ${formatBytes(total)}"
                            }
                            ffmpegPath = exe.path
                            ffmpegStatus = "설치했습니다"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ffmpegStatus = e.message
                        } finally {
                            installing = false
                        }
                    }
                }) { Text("지금 설치") }
            }
        }

        Section("앱 데이터") {
            Text(container.storage.appDir.path, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { DesktopActions.open(container.storage.appDir) }) { Text("열기") }
                OutlinedButton(onClick = { container.downloads.clearCompleted() }) { Text("완료 목록 비우기(파일 유지)") }
            }
        }

        HorizontalDivider()
        Text(
            "저작권이 있는 콘텐츠는 권리자의 허락 없이 내려받거나 배포하면 안 됩니다. 유튜브 지원은 NewPipe Extractor(GPL-3.0)를 사용하며 개인 사용 전용입니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

private fun chooseDirectory(current: String): String? {
    var result: String? = null
    val pick = {
        val chooser = JFileChooser(current).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "저장 폴더 선택"
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) result = chooser.selectedFile.path
    }
    if (SwingUtilities.isEventDispatchThread()) pick() else SwingUtilities.invokeAndWait(pick)
    return result
}
