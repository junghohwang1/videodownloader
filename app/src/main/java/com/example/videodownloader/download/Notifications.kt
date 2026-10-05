package com.example.videodownloader.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.videodownloader.MainActivity
import com.example.videodownloader.MainTab
import com.example.videodownloader.R
import com.example.videodownloader.player.PlayerActivity
import com.example.videodownloader.util.Storage
import java.io.File

object Notifications {
    private const val CHANNEL_PROGRESS = "downloads"
    private const val CHANNEL_COMPLETED = "completed"
    const val ID_PROGRESS = 1001

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_PROGRESS, "다운로드 진행", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_COMPLETED, "다운로드 완료", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    fun buildProgress(context: Context, title: String, text: String?, progress: Float?): Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .setContentIntent(mainIntent(context, MainTab.PROGRESS, 0))
            .build()

    fun showCompleted(context: Context, id: Long, file: File, mimeType: String?, isPrivate: Boolean) {
        val requestCode = id.toInt()
        val contentIntent = when {
            // 비공개 파일은 잠금 화면을 거치도록 비공개 탭으로 보낸다.
            isPrivate -> mainIntent(context, MainTab.PRIVATE, requestCode)
            Storage.isPlayable(mimeType, file.name) -> PendingIntent.getActivity(
                context,
                requestCode,
                PlayerActivity.intent(context, file.path, file.name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            else -> mainIntent(context, MainTab.COMPLETED, requestCode)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETED)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("다운로드 완료")
            .setContentText(if (isPrivate) "비공개 폴더에 저장했습니다" else file.name)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        notifySafe(context, 2000 + requestCode, notification)
    }

    fun notifySafe(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun mainIntent(context: Context, tab: MainTab, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            MainActivity.intent(context, tab),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
