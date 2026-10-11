package com.example.videodownloader.subtitle

import android.content.Context
import com.example.videodownloader.download.downloadRanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 음성 인식 모델(Whisper small, 5비트 양자화 약 190MB). 처음 자막을 만들 때 한 번 받아 앱 저장소에 둔다.
 * 끊기면 이어받고, 다 받으면 해시로 손상·변조 여부를 확인한다.
 */
class SpeechModel(context: Context, private val client: OkHttpClient) {

    val file = File(context.filesDir, "models/$NAME")
    private val part = File(file.path + ".part")

    /** 말소리 구간 감지(VAD) 모델(Silero, 약 0.9MB). 무음·음악 구간을 건너뛰어 인식을 빠르게 한다. */
    private val vadFile = File(context.filesDir, "models/$VAD_NAME")

    val isReady: Boolean get() = file.length() == SIZE

    /** 받아 둔 모델과 받다 만 조각의 크기. */
    val storedBytes: Long get() = file.length() + part.length() + vadFile.length()

    /** VAD 모델을 준비한다. 받지 못하면 null(그때는 무음 건너뛰기 없이 인식한다). */
    suspend fun ensureVad(): File? {
        if (vadFile.length() == VAD_SIZE) return vadFile
        return runCatching {
            val vadPart = File(vadFile.path + ".part")
            vadPart.delete()
            client.downloadRanged(VAD_URL, emptyMap(), vadPart, VAD_SIZE) { _, _ -> }
            withContext(Dispatchers.IO) {
                if (sha256(vadPart) != VAD_SHA256) {
                    vadPart.delete()
                    throw IOException("VAD 모델 파일이 손상됐습니다")
                }
                vadFile.delete()
                if (!vadPart.renameTo(vadFile)) throw IOException("VAD 모델을 저장할 수 없습니다")
            }
            vadFile
        }.getOrNull()
    }

    suspend fun ensure(onProgress: (Float) -> Unit): File {
        if (isReady) return file
        client.downloadRanged(URL, emptyMap(), part, SIZE) { downloaded, total ->
            if (total > 0) onProgress(downloaded.toFloat() / total)
        }
        withContext(Dispatchers.IO) {
            if (sha256(part) != SHA256) {
                part.delete()
                throw IOException("음성 인식 모델 파일이 손상됐습니다. 다시 시도해 주세요")
            }
            file.delete()
            if (!part.renameTo(file)) throw IOException("음성 인식 모델을 저장할 수 없습니다")
        }
        return file
    }

    fun delete() {
        file.delete()
        part.delete()
        vadFile.delete()
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

    companion object {
        const val NAME = "ggml-small-q5_1.bin"
        const val URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$NAME"
        const val SIZE = 190_085_487L
        const val SHA256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"

        const val VAD_NAME = "ggml-silero-v5.1.2.bin"
        const val VAD_URL = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/$VAD_NAME"
        const val VAD_SIZE = 885_098L
        const val VAD_SHA256 = "29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf"
    }
}
