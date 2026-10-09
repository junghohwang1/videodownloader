package com.example.videodownloader.download

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Range 요청을 [chunkSize] 단위로 반복해 [part] 에 이어 쓴다.
 * 유튜브처럼 한 번에 통째로 받으면 속도를 제한하는 서버에 쓴다. 이미 받은 만큼은 건너뛴다(이어받기).
 * 서버가 Range 를 무시하고 200 을 주면 파일 전체를 한 번에 받는다.
 *
 * @return 최종 파일 크기
 */
suspend fun OkHttpClient.downloadRanged(
    url: String,
    headers: Map<String, String>,
    part: File,
    knownTotal: Long = -1,
    chunkSize: Long = 8L * 1024 * 1024,
    onProgress: suspend (downloaded: Long, total: Long) -> Unit,
): Long {
    part.parentFile?.mkdirs()
    var total = knownTotal
    var position = if (part.exists()) part.length() else 0L
    if (total in 1..position) return position

    while (total < 0 || position < total) {
        currentCoroutineContext().ensureActive()
        val end = (position + chunkSize - 1).let { if (total > 0) minOf(it, total - 1) else it }
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .header("Range", "bytes=$position-$end")
            .build()

        var received = 0L
        val wholeFile = executeCancellable(request) { response ->
            if (response.code == 416) {
                total = position
                return@executeCancellable true
            }
            if (!response.isSuccessful) throw IOException("서버 응답 오류 (HTTP ${response.code})")
            val partial = response.code == 206
            if (!partial) position = 0
            parseContentRangeTotal(response.header("Content-Range"))?.let { total = it }
            if (!partial) total = response.body.contentLength()

            FileOutputStream(part, partial).use { out ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        position += read
                        received += read
                        onProgress(position, total)
                    }
                }
            }
            !partial
        }
        if (wholeFile) break
        // 전체 크기를 모르는 상태에서 요청보다 적게 왔다면 끝까지 받은 것이다.
        if (total < 0 && received < end - (position - received) + 1) break
        if (received == 0L) throw IOException("서버가 데이터를 보내지 않았습니다")
    }
    if (total > 0 && position < total) throw IOException("연결이 끊겨 다운로드가 완료되지 않았습니다")
    return position
}
