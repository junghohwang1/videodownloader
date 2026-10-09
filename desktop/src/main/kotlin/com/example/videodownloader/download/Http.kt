package com.example.videodownloader.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 웹페이지에서 영상을 받을 때와 같은 조건(User-Agent, Referer)으로 요청해야
 * 핫링크 차단 페이지에서도 내려받을 수 있다. (쿠키는 내장 브라우저가 붙는 2단계에서 연결한다.)
 */
fun Request.Builder.applyMediaHeaders(url: String, userAgent: String?, referer: String?): Request.Builder {
    header("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT)
    referer?.takeIf { it.isNotBlank() }?.let { header("Referer", it) }
    return this
}

const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

/**
 * 블로킹 OkHttp 호출을 IO 스레드에서 실행하되, 코루틴이 취소되면 즉시 Call 을 끊는다.
 * (본문을 읽는 도중 일시정지해도 read() 가 바로 풀린다.)
 */
suspend fun <T> OkHttpClient.executeCancellable(request: Request, block: suspend (Response) -> T): T =
    withContext(Dispatchers.IO) {
        val call = newCall(request)
        coroutineScope {
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                call.execute().use { block(it) }
            } finally {
                watcher.cancel()
            }
        }
    }

fun parseContentRangeTotal(header: String?): Long? =
    header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
