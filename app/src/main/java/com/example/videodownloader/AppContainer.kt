package com.example.videodownloader

import android.content.Context
import com.example.videodownloader.browser.TabStore
import com.example.videodownloader.data.db.AppDatabase
import com.example.videodownloader.data.settings.SettingsRepository
import com.example.videodownloader.download.DownloadManager
import com.example.videodownloader.files.LibraryActions
import com.example.videodownloader.util.Storage
import com.example.videodownloader.youtube.YouTubeSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** 앱 전역 의존성. 규모가 작아 DI 프레임워크 없이 수동으로 조립한다. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase = AppDatabase.create(appContext)
    val settings = SettingsRepository(appContext)
    val storage = Storage(appContext)
    val tabStore = TabStore(File(appContext.filesDir, "tabs.json"), File(appContext.filesDir, "tab_states"))

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    init {
        YouTubeSupport.init(httpClient)
    }

    val downloadManager = DownloadManager(
        context = appContext,
        dao = database.downloadDao(),
        client = httpClient,
        settings = settings,
        storage = storage,
        scope = appScope,
    )

    val library = LibraryActions(database.downloadDao(), storage, downloadManager)
}
