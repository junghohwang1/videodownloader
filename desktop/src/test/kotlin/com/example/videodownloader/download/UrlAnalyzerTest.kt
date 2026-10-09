package com.example.videodownloader.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlAnalyzerTest {

    @Test
    fun findsVideoUrlsInHtmlAndJson() {
        val html = """
            <html><head>
            <meta property="og:video" content="https://cdn.example.com/a/video.mp4?token=1&amp;x=2">
            </head><body>
            <video><source src="/media/clip.webm" type="video/webm"></video>
            <script>var cfg = {"hls":"https:\/\/stream.example.com\/live\/master.m3u8"};</script>
            <img src="poster.jpg"><a href="https://example.com/seg001.ts">x</a>
            </body></html>
        """.trimIndent()

        val found = UrlAnalyzer.findCandidates(html, "https://example.com/watch/1")
        assertEquals(
            listOf(
                "https://cdn.example.com/a/video.mp4?token=1&x=2",
                "https://example.com/media/clip.webm",
                "https://stream.example.com/live/master.m3u8",
            ),
            found,
        )
    }

    @Test
    fun normalizesUrlsWithoutScheme() {
        assertEquals("https://example.com/v.mp4", UrlAnalyzer.normalize("  example.com/v.mp4 "))
        assertTrue(UrlAnalyzer.normalize("not a url") == null)
    }
}
