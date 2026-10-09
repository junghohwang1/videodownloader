package com.example.videodownloader

import com.example.videodownloader.data.DownloadStore
import com.example.videodownloader.data.SettingsRepository
import com.example.videodownloader.download.DownloadManager
import com.example.videodownloader.download.Ffmpeg
import com.example.videodownloader.download.UrlAnalyzer
import com.example.videodownloader.util.Storage
import com.example.videodownloader.youtube.YouTubeSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** 수동 DI. 앱 전체에서 하나만 만든다. */
class AppContainer {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val dataDir = Storage().appDir
    val settings = SettingsRepository(File(dataDir, "settings.json"))
    val storage = Storage(settings)
    val ffmpeg = Ffmpeg(client, File(dataDir, "ffmpeg"))
    val store = DownloadStore(File(dataDir, "downloads.json"), scope)
    val analyzer = UrlAnalyzer(client)
    val downloads = DownloadManager(store, client, settings, storage, ffmpeg, scope)

    init {
        YouTubeSupport.init(client)
        downloads.recoverInterrupted()
    }

    fun shutdown() {
        downloads.pauseAll()
        store.flush()
    }
}
