package com.example.videodownloader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageFileNameTest {
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    @Test
    fun longCjkTitleFitsFileSystemLimit() {
        // 한자 150자(450바이트) 제목: 글자 수는 120 미만이 아니어도 바이트로 잘라야 한다.
        val title = "台南貼心正妹六星級定點服務".repeat(12) + ".mp4"
        val name = Storage.sanitizeFileName(title)
        assertTrue("${bytes(name)} bytes", bytes(name) <= 200)
        assertTrue(name.endsWith(".mp4"))
        // ".part", " (99)", ".srt" 를 붙여도 255바이트 이내
        assertTrue(bytes(name.removeSuffix(".mp4") + " (99).mp4.part") <= 255)
    }

    @Test
    fun keepsShortNamesAndReplacesIllegalCharacters() {
        assertEquals("a_b_c.mp4", Storage.sanitizeFileName("a/b:c.mp4"))
        assertEquals("짧은 제목.mp4", Storage.sanitizeFileName("짧은   제목.mp4"))
        assertEquals("video", Storage.sanitizeFileName("..."))
    }

    @Test
    fun doesNotSplitSurrogatePairs() {
        val name = Storage.sanitizeFileName("😀".repeat(80) + ".mp4")
        assertTrue(bytes(name) <= 200)
        assertEquals(name, String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }
}
