package com.example.videodownloader.download

import com.example.videodownloader.data.DownloadStatus
import com.example.videodownloader.data.DownloadStore
import com.example.videodownloader.data.MediaKind
import com.example.videodownloader.data.SettingsRepository
import com.example.videodownloader.util.Storage
import com.example.videodownloader.youtube.YouTubeSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * 실제 사이트로 분석 → 다운로드 → (필요 시 ffmpeg) 까지 확인한다. 네트워크가 필요해 기본은 건너뛴다.
 * 실행: E2E_TEST=1 gradlew test --tests '*EndToEndTest*'
 */
class EndToEndTest {

    private fun run(input: String, pick: (List<DownloadOption>) -> DownloadOption, expectExt: String) = runBlocking {
        assumeTrue(System.getenv("E2E_TEST") == "1")
        val dir = Files.createTempDirectory("vd-e2e").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
        YouTubeSupport.init(client)
        val settings = SettingsRepository(File(dir, "settings.json")).apply {
            update { it.copy(downloadDir = File(dir, "out").path) }
        }
        val storage = Storage(settings)
        val ffmpeg = Ffmpeg(client, File(storage.appDir, "ffmpeg"))
        val manager = DownloadManager(DownloadStore(File(dir, "downloads.json"), scope), client, settings, storage, ffmpeg, scope)

        val result = UrlAnalyzer(client).analyze(input)
        println("분석: ${result.title} / ${result.options.map { "${it.label}(${it.kind}, ${it.sizeBytes})" }}")
        val option = pick(result.options)
        val id = manager.enqueue(
            NewDownload(option.url, option.kind, "${result.title}.${option.ext}", result.title, result.pageUrl, result.referer, formatSpec = option.formatSpec),
        )
        val done = withTimeout(15 * 60_000) {
            manager.downloads.first { list -> list.first { it.id == id }.status.let { it == DownloadStatus.COMPLETED || it == DownloadStatus.FAILED } }
                .first { it.id == id }
        }
        println("결과: ${done.status} ${done.filePath} ${done.totalBytes}B ${done.error ?: ""}")
        scope.cancel()
        assertEquals(done.error, DownloadStatus.COMPLETED, done.status)
        val file = File(done.filePath)
        assertTrue(file.length() > 10_000)
        assertEquals(expectExt, file.extension)
        if (expectExt == "mp4") assertTrue("MP4 헤더(ftyp)가 아닙니다", String(file.readBytes().copyOfRange(4, 8)) == "ftyp")
    }

    @Test
    fun webPageWithVideoTag() = run(
        "https://www.w3schools.com/html/html5_video.asp",
        { options -> options.first { it.ext == "mp4" } },
        "mp4",
    )

    @Test
    fun youtubeVideoWithMux() = run(
        "https://www.youtube.com/watch?v=jNQXAC9IVRw",
        { options -> options.first { it.kind == MediaKind.YOUTUBE && it.formatSpec!!.startsWith("v=") } },
        "mp4",
    )

    @Test
    fun youtubeAudioOnly() = run(
        "https://youtu.be/jNQXAC9IVRw",
        { options -> options.first { it.ext == "m4a" } },
        "m4a",
    )

    @Test
    fun hlsTransportStreamRemux() = run(
        "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8",
        { options -> options.minBy { if (it.sizeBytes > 0) it.sizeBytes else Long.MAX_VALUE } },
        "mp4",
    )
}
