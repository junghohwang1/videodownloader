package com.example.videodownloader.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * 재인코딩 없는 컨테이너 변환은 ffmpeg 에 맡긴다(Android 판의 MediaMuxer·Media3 Transformer 대체).
 * 찾는 순서: 앱 설치 폴더 → %LOCALAPPDATA%\VideoDownloader\ffmpeg → PATH.
 * 없으면 처음 필요할 때 자동으로 내려받는다(약 110MB, 한 번만).
 */
class Ffmpeg(private val client: OkHttpClient, private val toolsDir: File) {

    private val installLock = Mutex()

    fun locate(): File? {
        val candidates = buildList {
            System.getProperty("compose.application.resources.dir")?.let { add(File(it, "ffmpeg.exe")) }
            System.getProperty("jpackage.app-path")?.let { File(it).parentFile?.let { dir -> add(File(dir, "ffmpeg.exe")) } }
            add(File(toolsDir, "ffmpeg.exe"))
            System.getenv("PATH")?.split(File.pathSeparatorChar)?.forEach { add(File(it, "ffmpeg.exe")) }
        }
        return candidates.firstOrNull { it.isFile }
    }

    val isAvailable: Boolean get() = locate() != null

    /** ffmpeg 를 찾고, 없으면 내려받아 설치한다. */
    suspend fun ensure(onProgress: suspend (downloaded: Long, total: Long) -> Unit = { _, _ -> }): File {
        locate()?.let { return it }
        return installLock.withLock { locate() ?: install(onProgress) }
    }

    /** TS → MP4. 실패하면 false 를 돌려주고 호출자는 TS 를 그대로 둔다. */
    suspend fun remuxToMp4(input: File, output: File): Boolean = runCatching {
        run(listOf("-i", input.path, "-map", "0", "-dn", "-c", "copy", "-bsf:a", "aac_adtstoasc", "-movflags", "+faststart", output.path))
        output.isFile && output.length() > 0
    }.getOrElse {
        output.delete()
        if (it is kotlinx.coroutines.CancellationException) throw it
        false
    }

    /** 영상 전용 + 음성 전용 파일을 MP4 하나로 합친다. */
    suspend fun mux(video: File, audio: File, output: File) {
        try {
            run(
                listOf(
                    "-i", video.path, "-i", audio.path,
                    "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-movflags", "+faststart", output.path,
                ),
            )
        } catch (e: Exception) {
            output.delete()
            throw if (e is IOException || e is kotlinx.coroutines.CancellationException) e
            else IOException("영상과 음성을 합치지 못했습니다: ${e.message}", e)
        }
    }

    private suspend fun run(args: List<String>) {
        val exe = ensure()
        withContext(Dispatchers.IO) {
            val log = File.createTempFile("ffmpeg", ".log")
            val process = ProcessBuilder(listOf(exe.path, "-hide_banner", "-loglevel", "error", "-y") + args)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
            try {
                val code = runInterruptible { process.waitFor() }
                if (code != 0) {
                    val message = log.readText().lines().lastOrNull { it.isNotBlank() }.orEmpty()
                    throw IOException("ffmpeg 오류($code) $message".trim())
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
                log.delete()
            }
        }
    }

    private suspend fun install(onProgress: suspend (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        toolsDir.mkdirs()
        val zip = File(toolsDir, "ffmpeg.zip.part")
        var lastError: Exception? = null
        for (url in DOWNLOAD_URLS) {
            try {
                zip.delete()
                client.downloadRanged(url, emptyMap(), zip, chunkSize = 32L * 1024 * 1024, onProgress = onProgress)
                val exe = extractExe(zip)
                zip.delete()
                return@withContext exe
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        zip.delete()
        throw IOException("ffmpeg 를 설치하지 못했습니다: ${lastError?.message}", lastError)
    }

    private suspend fun extractExe(zip: File): File {
        val target = File(toolsDir, "ffmpeg.exe")
        val tmp = File(toolsDir, "ffmpeg.exe.tmp")
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = input.nextEntry ?: break
                if (!entry.isDirectory && entry.name.substringAfterLast('/').equals("ffmpeg.exe", ignoreCase = true)) {
                    tmp.outputStream().use { input.copyTo(it) }
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) throw IOException("ffmpeg.exe 를 저장할 수 없습니다")
                    return target
                }
            }
        }
        throw IOException("압축 파일에 ffmpeg.exe 가 없습니다")
    }

    private companion object {
        val DOWNLOAD_URLS = listOf(
            "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip",
            "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-lgpl.zip",
        )
    }
}
