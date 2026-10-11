package com.example.videodownloader.subtitle

import com.example.videodownloader.download.downloadRanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 음성 인식 도구. 처음 자막을 만들 때 한 번만 받는다(ffmpeg 처럼).
 * - whisper.cpp Windows 실행 파일(약 9MB, b5130 = v1.9.4) → %LOCALAPPDATA%\VideoDownloader\whisper
 * - 음성 인식 모델 Whisper small 5비트(약 190MB) → %LOCALAPPDATA%\VideoDownloader\models (Android 판과 같은 파일)
 * 받은 파일은 해시로 손상·변조 여부를 확인한다.
 */
class WhisperTools(private val client: OkHttpClient, appDir: File) {

    private val binDir = File(appDir, "whisper")
    val modelFile = File(appDir, "models/$MODEL_NAME")
    private val lock = Mutex()

    val cli: File get() = File(binDir, "whisper-cli.exe")

    val isModelReady: Boolean get() = modelFile.length() == MODEL_SIZE

    /** 받아 둔 모델과 실행 파일 크기. */
    val storedBytes: Long
        get() = modelFile.length() + File(modelFile.path + ".part").length() +
            (binDir.listFiles()?.sumOf { it.length() } ?: 0L)

    suspend fun ensure(onProgress: suspend (message: String, fraction: Float?) -> Unit): Pair<File, File> = lock.withLock {
        if (!cli.isFile) installBinaries(onProgress)
        if (!isModelReady) downloadModel(onProgress)
        cli to modelFile
    }

    fun deleteModel() {
        modelFile.delete()
        File(modelFile.path + ".part").delete()
    }

    private suspend fun installBinaries(onProgress: suspend (String, Float?) -> Unit) = withContext(Dispatchers.IO) {
        binDir.mkdirs()
        val zip = File(binDir, "whisper.zip.part")
        zip.delete()
        client.downloadRanged(BIN_URL, emptyMap(), zip, BIN_SIZE) { done, total ->
            onProgress("음성 인식 프로그램 받는 중", if (total > 0) done.toFloat() / total else null)
        }
        if (sha256(zip) != BIN_SHA256) {
            zip.delete()
            throw IOException("음성 인식 프로그램 파일이 손상됐습니다. 다시 시도해 주세요")
        }
        // 실행에 필요한 whisper-cli.exe 와 DLL 만 꺼낸다.
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || !(name.endsWith(".dll", true) || name.equals("whisper-cli.exe", true))) continue
                File(binDir, name).outputStream().use { input.copyTo(it) }
            }
        }
        zip.delete()
        if (!cli.isFile) throw IOException("음성 인식 프로그램을 설치하지 못했습니다")
    }

    private suspend fun downloadModel(onProgress: suspend (String, Float?) -> Unit) = withContext(Dispatchers.IO) {
        modelFile.parentFile?.mkdirs()
        val part = File(modelFile.path + ".part")
        client.downloadRanged(MODEL_URL, emptyMap(), part, MODEL_SIZE) { done, total ->
            onProgress("음성 인식 모델 받는 중", if (total > 0) done.toFloat() / total else null)
        }
        onProgress("음성 인식 모델 확인 중", null)
        if (sha256(part) != MODEL_SHA256) {
            part.delete()
            throw IOException("음성 인식 모델 파일이 손상됐습니다. 다시 시도해 주세요")
        }
        modelFile.delete()
        if (!part.renameTo(modelFile)) throw IOException("음성 인식 모델을 저장할 수 없습니다")
    }

    private fun sha256(f: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BIN_URL = "https://github.com/ggml-org/whisper.cpp/releases/download/b5130/whisper-bin-x64.zip"
        const val BIN_SIZE = 8_573_270L
        const val BIN_SHA256 = "f9ec6c52a2e949b62ab51fa21d0d497958f9e41c3010c157c4e42932d5316f3c"

        const val MODEL_NAME = "ggml-small-q5_1.bin"
        const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$MODEL_NAME"
        const val MODEL_SIZE = 190_085_487L
        const val MODEL_SHA256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
    }
}
