package com.example.videodownloader.download

import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.videodownloader.appContainer
import com.example.videodownloader.data.db.progressFraction
import com.example.videodownloader.util.formatBytes
import kotlinx.coroutines.launch

/**
 * 다운로드가 진행되는 동안 프로세스가 종료되지 않도록 붙잡아 두는 포그라운드 서비스.
 * 실제 작업은 DownloadManager 가 하고, 여기서는 진행 알림만 갱신한다.
 */
class DownloadService : LifecycleService() {

    private var observing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startInForeground()
        if (!observing) {
            observing = true
            lifecycleScope.launch {
                appContainer.downloadManager.downloads.collect { list ->
                    val active = list.filter { it.status.isActive }
                    if (active.isEmpty()) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collect
                    }
                    val title = if (active.size == 1) active.first().fileName else "${active.size}개 다운로드 중"
                    val fractions = active.mapNotNull { it.progressFraction() }
                    val progress = if (fractions.size == active.size) fractions.average().toFloat() else null
                    val downloaded = active.sumOf { it.downloadedBytes }
                    val text = buildString {
                        progress?.let { append("${(it * 100).toInt()}% · ") }
                        append(formatBytes(downloaded))
                    }
                    Notifications.notifySafe(
                        this@DownloadService,
                        Notifications.ID_PROGRESS,
                        Notifications.buildProgress(this@DownloadService, title, text, progress),
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        ServiceCompat.startForeground(
            this,
            Notifications.ID_PROGRESS,
            Notifications.buildProgress(this, "다운로드 준비 중", null, null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /** Android 15+: dataSync 포그라운드 서비스 시간 한도에 도달하면 모두 일시정지한다. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        appContainer.downloadManager.pauseAll()
        stopSelf()
    }
}
