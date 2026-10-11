package com.example.videodownloader

import com.example.videodownloader.browser.AdBlocker
import com.example.videodownloader.browser.BookmarkStore
import com.example.videodownloader.browser.BrowserEngine
import com.example.videodownloader.browser.BrowserTabs
import com.example.videodownloader.data.DownloadStore
import com.example.videodownloader.data.SettingsRepository
import com.example.videodownloader.download.DownloadManager
import com.example.videodownloader.download.Ffmpeg
import com.example.videodownloader.download.UrlAnalyzer
import com.example.videodownloader.subtitle.SubtitleGenerator
import com.example.videodownloader.subtitle.SubtitleTranslator
import com.example.videodownloader.subtitle.WhisperTools
import com.example.videodownloader.util.Storage
import com.example.videodownloader.youtube.YouTubeSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    val bookmarks = BookmarkStore(File(dataDir, "bookmarks.json"))
    val browserEngine = BrowserEngine(dataDir, scope)
    val adBlocker = AdBlocker(client, File(dataDir, "adblock-hosts.txt"), scope) { settings.current().blockAds }
    val browser = BrowserTabs(
        analyzer = analyzer,
        bookmarks = bookmarks,
        scope = scope,
        stateFile = File(dataDir, "tabs.json"),
        isAdRequest = adBlocker::isAdRequest,
        pageScript = adBlocker::pageScript,
        earlyScript = adBlocker::earlyScript,
    )

    val subtitles = SubtitleGenerator(
        ffmpeg = ffmpeg,
        tools = WhisperTools(client, dataDir),
        translator = SubtitleTranslator(client),
        workDir = File(dataDir, "work"),
        scope = scope,
    )

    init {
        YouTubeSupport.init(client)
        downloads.recoverInterrupted()
        adBlocker.start()
        browserEngine.start()
        // 설정에서 켜 두면 영상 다운로드가 끝나는 대로 자막을 만든다.
        scope.launch {
            downloads.completed.collect { item ->
                val file = File(item.filePath)
                val isVideo = (item.mimeType ?: Storage.guessMime(file.name)).startsWith("video/")
                if (isVideo && settings.current().autoSubtitles && !Storage.subtitleFor(file).exists()) {
                    subtitles.enqueue(item.id, file, item.fileName)
                }
            }
        }
    }

    fun restartBrowserEngine() {
        browserEngine.retry()
    }

    fun shutdown() {
        downloads.pauseAll()
        store.flush()
        browser.dispose()
        browserEngine.dispose()
    }
}
