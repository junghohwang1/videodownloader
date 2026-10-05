package com.example.videodownloader

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.example.videodownloader.download.Notifications

class App : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        // 프로세스가 종료되며 중단된 다운로드는 "일시정지" 상태로 돌려 사용자가 재개하게 한다.
        container.downloadManager.recoverInterrupted()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
}

val Context.appContainer: AppContainer
    get() = (applicationContext as App).container
