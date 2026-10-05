package com.example.videodownloader.download

import com.example.videodownloader.data.db.DownloadEntity
import com.example.videodownloader.util.Storage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

fun interface ProgressListener {
    suspend fun onProgress(downloadedBytes: Long, totalBytes: Long, segmentsDone: Int, segmentsTotal: Int)
}

data class DownloadResult(val file: File, val mimeType: String?)

class UnsupportedMediaException(message: String) : IOException(message)

/** 단일 파일 다운로드. `.part` 에 이어 쓰고 Range 요청으로 이어받기를 지원한다. */
class HttpDownloader(private val client: OkHttpClient) {

    suspend fun download(task: DownloadEntity, listener: ProgressListener): DownloadResult {
        val target = File(task.filePath)
        val part = File(task.filePath + Storage.PART)
        part.parentFile?.mkdirs()
        val existing = if (part.exists()) part.length() else 0L

        val request = Request.Builder()
            .url(task.url)
            .applyMediaHeaders(task.url, task.userAgent, task.referer)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()

        var mimeType = task.mimeType
        client.executeCancellable(request) { response ->
            // 이미 끝까지 받은 상태에서 Range 를 요청하면 416 이 온다.
            if (response.code == 416 && existing > 0) return@executeCancellable
            if (!response.isSuccessful) throw IOException("서버 응답 오류 (HTTP ${response.code})")

            val body = response.body
            body.contentType()?.let { mimeType = "${it.type}/${it.subtype}" }

            val resumed = existing > 0 && response.code == 206
            val start = if (resumed) existing else 0L
            val total = if (resumed) {
                parseContentRangeTotal(response.header("Content-Range"))
                    ?: body.contentLength().takeIf { it >= 0 }?.plus(start)
                    ?: -1L
            } else {
                body.contentLength()
            }

            var downloaded = start
            FileOutputStream(part, resumed).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        listener.onProgress(downloaded, total, 0, 0)
                    }
                }
            }
            if (total > 0 && downloaded < total) throw IOException("연결이 끊겨 다운로드가 완료되지 않았습니다")
        }

        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("파일을 저장할 수 없습니다")
        return DownloadResult(target, mimeType?.takeUnless { it == "application/octet-stream" })
    }
}
