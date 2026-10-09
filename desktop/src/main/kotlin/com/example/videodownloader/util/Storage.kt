package com.example.videodownloader.util

import com.example.videodownloader.data.SettingsRepository
import java.io.File
import java.net.URLConnection

/**
 * 저장 위치 관리.
 * - 앱 데이터: %LOCALAPPDATA%\VideoDownloader (목록·설정·임시 조각·ffmpeg)
 * - 다운로드: 설정에서 고른 폴더, 기본은 사용자 동영상 폴더\VideoDownloader
 */
class Storage(private val settings: SettingsRepository? = null) {

    val appDir: File = File(
        System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"),
        "VideoDownloader",
    ).also { it.mkdirs() }

    val defaultDownloadDir: File get() = File(System.getProperty("user.home"), "Videos/$EXPORT_FOLDER")

    val downloadDir: File
        get() = (settings?.current()?.downloadDir?.takeIf { it.isNotBlank() }?.let(::File) ?: defaultDownloadDir)
            .also { it.mkdirs() }

    /** 조각 파일 등 다운로드 중간 산출물을 두는 폴더(완료·삭제 시 지운다). */
    fun tempDir(downloadId: Long): File = File(appDir, "work/$downloadId")

    /** 이름이 겹치지 않는 파일 경로를 고르고, 빈 .part 파일을 만들어 이름을 선점한다. */
    @Synchronized
    fun reserveFile(dir: File, desiredName: String): File {
        val file = uniqueFile(dir, desiredName)
        File(file.path + PART).createNewFile()
        return file
    }

    @Synchronized
    fun uniqueFile(dir: File, desiredName: String): File {
        dir.mkdirs()
        val clean = sanitizeFileName(desiredName)
        val base = clean.substringBeforeLast('.', clean)
        val ext = clean.substringAfterLast('.', "").let { if (it.isEmpty() || it == clean) "" else ".$it" }
        var i = 0
        while (true) {
            val name = if (i == 0) "$base$ext" else "$base ($i)$ext"
            val candidate = File(dir, name)
            if (!candidate.exists() && !File(candidate.path + PART).exists()) return candidate
            i++
        }
    }

    companion object {
        const val PART = ".part"
        const val EXPORT_FOLDER = "VideoDownloader"
        private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
        private val SPACES = Regex("\\s+")

        /** Windows 예약 이름(CON, NUL, COM1 …)은 파일로 만들 수 없다. */
        private val RESERVED = Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$", RegexOption.IGNORE_CASE)

        fun sanitizeFileName(name: String): String {
            var cleaned = name.replace(ILLEGAL, "_").replace(SPACES, " ").trim().trim('.')
            if (cleaned.isEmpty()) return "video"
            if (RESERVED.matches(cleaned.substringBefore('.'))) cleaned = "_$cleaned"
            if (cleaned.length <= 120) return cleaned
            val ext = cleaned.substringAfterLast('.', "")
            return if (ext.isNotEmpty() && ext.length <= 5) {
                cleaned.substringBeforeLast('.').take(110).trimEnd() + "." + ext
            } else {
                cleaned.take(120)
            }
        }

        fun guessMime(fileName: String): String =
            URLConnection.guessContentTypeFromName(fileName)
                ?: when (fileName.substringAfterLast('.', "").lowercase()) {
                    "mp4", "m4v" -> "video/mp4"
                    "webm" -> "video/webm"
                    "mkv" -> "video/x-matroska"
                    "ts" -> "video/mp2t"
                    "m4a" -> "audio/mp4"
                    "mp3" -> "audio/mpeg"
                    else -> "video/mp4"
                }

        fun extensionForMime(mime: String?): String? = when (mime?.lowercase()?.substringBefore(';')?.trim()) {
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            "video/x-matroska" -> "mkv"
            "video/3gpp" -> "3gp"
            "video/x-flv" -> "flv"
            "video/x-msvideo" -> "avi"
            "video/x-ms-wmv" -> "wmv"
            "audio/mp4" -> "m4a"
            "audio/mpeg" -> "mp3"
            else -> null
        }
    }
}
