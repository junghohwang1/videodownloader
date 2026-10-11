package com.example.videodownloader.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.videodownloader.BuildConfig

/** 새 버전 안내. [progress] 가 null 이 아니면 설치 파일을 받는 중이다. */
@Composable
fun UpdateDialog(info: UpdateInfo, progress: Float?, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    val downloading = progress != null
    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("새 버전 ${info.version}") },
        text = {
            Column {
                Text("지금 버전: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                if (info.notes.isNotBlank()) {
                    Text(
                        info.notes,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                if (progress != null) {
                    Text("받는 중…", modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                    if (progress >= 0f) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    Text(
                        "처음 업데이트할 때 「이 출처 허용」을 물으면 「설정」 > 허용 켜기 > 뒤로 > 「업데이트」를 누르세요.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdate, enabled = !downloading) { Text("업데이트") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !downloading) { Text("나중에") }
        },
    )
}
