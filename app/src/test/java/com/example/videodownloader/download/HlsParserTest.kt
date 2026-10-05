package com.example.videodownloader.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsParserTest {

    @Test
    fun parsesMasterPlaylistVariants() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            https://cdn.example.com/high/index.m3u8
        """.trimIndent()

        assertTrue(HlsParser.isMaster(text))
        val variants = HlsParser.parseMaster(text, "https://example.com/video/master.m3u8")
        assertEquals(2, variants.size)
        assertEquals("https://example.com/video/low/index.m3u8", variants[0].url)
        assertEquals(800_000L, variants[0].bandwidth)
        assertEquals("640x360", variants[0].resolution)
        assertEquals("https://cdn.example.com/high/index.m3u8", variants.maxBy { it.bandwidth }.url)
    }

    @Test
    fun parsesMediaPlaylistWithKeyAndSequence() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:9.5,
            seg7.ts
            #EXTINF:10.0,
            seg8.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4.5,
            /abs/seg9.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = HlsParser.parseMedia(text, "https://example.com/a/b/index.m3u8")
        assertTrue(playlist.isEnded)
        assertEquals(3, playlist.segments.size)
        assertEquals(24.0, playlist.durationSec, 0.001)

        val first = playlist.segments[0]
        assertEquals("https://example.com/a/b/seg7.ts", first.url)
        assertEquals(7L, first.sequence)
        assertEquals("AES-128", first.key?.method)
        assertEquals("https://example.com/a/b/key.bin", first.key?.uri)
        assertNull(first.key?.iv)

        assertEquals(8L, playlist.segments[1].sequence)
        assertNull(playlist.segments[2].key)
        assertEquals("https://example.com/abs/seg9.ts", playlist.segments[2].url)
    }

    @Test
    fun parsesByteRangesAndInitMap() {
        val text = """
            #EXTM3U
            #EXT-X-MAP:URI="main.mp4",BYTERANGE="720@0"
            #EXTINF:5,
            #EXT-X-BYTERANGE:1000@720
            main.mp4
            #EXTINF:5,
            #EXT-X-BYTERANGE:500
            main.mp4
        """.trimIndent()

        val playlist = HlsParser.parseMedia(text, "https://example.com/v/index.m3u8")
        assertFalse(playlist.isEnded)
        assertNotNull(playlist.initSegment)
        assertEquals(0L..719L, playlist.initSegment?.byteRange)
        assertEquals(720L..1719L, playlist.segments[0].byteRange)
        assertEquals(1720L..2219L, playlist.segments[1].byteRange)
    }

    @Test
    fun parsesQuotedAttributesWithCommas() {
        val attrs = HlsParser.parseAttributes("""METHOD=AES-128,URI="https://k.example.com/key?a=1,b=2",IV=0x1A""")
        assertEquals("AES-128", attrs["METHOD"])
        assertEquals("https://k.example.com/key?a=1,b=2", attrs["URI"])
        assertEquals("0x1A", attrs["IV"])
    }

    @Test
    fun explicitIvIsLeftPadded() {
        val playlist = HlsParser.parseMedia(
            "#EXT-X-KEY:METHOD=AES-128,URI=\"k\",IV=0x01\n#EXTINF:1,\ns.ts\n#EXT-X-ENDLIST",
            "https://e.com/i.m3u8",
        )
        val expected = ByteArray(16).also { it[15] = 1 }
        assertArrayEquals(expected, playlist.segments[0].key?.iv)
    }

    @Test
    fun sequenceIvIsBigEndian() {
        val iv = HlsParser.sequenceIv(0x0102L)
        assertEquals(16, iv.size)
        assertEquals(0x01.toByte(), iv[14])
        assertEquals(0x02.toByte(), iv[15])
        assertEquals(0.toByte(), iv[0])
    }
}
