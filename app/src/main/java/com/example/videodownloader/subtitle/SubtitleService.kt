package com.example.videodownloader.subtitle

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.videodownloader.appContainer
import com.example.videodownloader.download.Notifications
import kotlinx.coroutines.launch

/**
 * 자막을 만드는 동안 앱이 백그라운드로 가도 멈추지 않도록 붙잡아 두는 포그라운드 서비스.
 * 실제 작업은 SubtitleGenerator 가 하고, 여기서는 진행 알림만 갱신한다.
 */
class SubtitleService : LifecycleService() {

    private var observing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startInForeground()
        if (!observing) {
            observing = true
            lifecycleScope.launch {
                appContainer.subtitles.jobs.collect { jobs ->
                    if (jobs.isEmpty()) {
                        ServiceCompat.stopForeground(this@SubtitleService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collect
                    }
                    val current = jobs.values.firstOrNull { it.phase != SubtitleGenerator.Phase.WAITING }
                        ?: jobs.values.first()
                    val title = if (jobs.size == 1) "자막 만드는 중" else "자막 만드는 중 (${jobs.size}개)"
                    val text = listOfNotNull(
                        current.phase.label,
                        current.fraction?.let { "${(it * 100).toInt()}%" },
                        current.detail,
                    ).joinToString(" · ")
                    Notifications.notifySafe(
                        this@SubtitleService,
                        Notifications.ID_SUBTITLE,
                        Notifications.buildProgress(this@SubtitleService, title, text, current.fraction),
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        ServiceCompat.startForeground(
            this,
            Notifications.ID_SUBTITLE,
            Notifications.buildProgress(this, "자막 만드는 중", null, null),
            type,
        )
    }

    /** Android 15+: 포그라운드 서비스 시간 한도에 도달하면 멈춘다. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        appContainer.subtitles.cancelAll()
        stopSelf()
    }
}
