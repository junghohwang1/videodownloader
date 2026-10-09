package com.example.videodownloader.youtube

import com.example.videodownloader.download.downloadRanged
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class YouTubeSupportTest {

    @Test
    fun extractsVideoIdFromCommonUrls() {
        val id = "dQw4w9WgXcQ"
        listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://m.youtube.com/watch?v=$id&t=42s",
            "https://youtube.com/watch?feature=share&v=$id",
            "https://youtu.be/$id?si=abc",
            "https://www.youtube.com/shorts/$id",
            "https://m.youtube.com/shorts/$id?feature=share",
            "https://www.youtube.com/live/$id",
            "https://www.youtube.com/embed/$id",
            "https://music.youtube.com/watch?v=$id&list=RD",
        ).forEach { assertEquals(it, id, YouTubeSupport.videoId(it)) }

        assertNull(YouTubeSupport.videoId("https://www.youtube.com/"))
        assertNull(YouTubeSupport.videoId("https://www.youtube.com/results?search_query=cat"))
        assertNull(YouTubeSupport.videoId("https://www.youtube.com/watch?v=short"))
        assertNull(YouTubeSupport.videoId("https://example.com/watch?v=$id"))
    }

    @Test
    fun specRoundTrip() {
        listOf("v=137;a=140", "p=18", "a=140").forEach {
            assertEquals(it, YtSpec.parse(it).encode())
        }
        assertEquals(137, YtSpec.parse("v=137;a=140").videoItag)
    }

    /**
     * 실제 유튜브에 접속하는 확인용 테스트. 네트워크가 필요하므로 YT_ONLINE_TEST=1 일 때만 실행한다.
     */
    @Test
    fun resolvesAndDownloadsFirstChunkOnline() = runBlocking {
        assumeTrue(System.getenv("YT_ONLINE_TEST") == "1")
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        YouTubeSupport.init(client)

        val video = YouTubeSupport.resolve("https://www.youtube.com/watch?v=jNQXAC9IVRw")
        println("title=${video.title} duration=${video.durationSec} formats=${video.formats}")
        assertTrue(video.formats.isNotEmpty())

        val best = video.formats.first()
        val streams = YouTubeSupport.resolveStreams(video.watchUrl, YtSpec.parse(best.spec))
        listOfNotNull(streams.video, streams.audio).forEach { ref ->
            val tmp = File.createTempFile("ytpart", ".part")
            tmp.delete()
            val started = System.nanoTime()
            val size = client.downloadRanged(
                url = ref.url,
                headers = YouTubeSupport.streamHeaders(ref.url),
                part = tmp,
                knownTotal = ref.contentLength,
                chunkSize = 1L shl 20,
            ) { _, _ -> }
            val seconds = (System.nanoTime() - started) / 1e9
            println("downloaded $size bytes (expected ${ref.contentLength}) in %.2fs".format(seconds))
            assertTrue(size > 0)
            if (ref.contentLength > 0) assertEquals(ref.contentLength, size)
            tmp.delete()
        }
    }
}
