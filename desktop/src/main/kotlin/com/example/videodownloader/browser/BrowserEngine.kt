package com.example.videodownloader.browser

import com.example.videodownloader.download.cookieProvider
import dev.datlag.kcef.KCEF
import dev.datlag.kcef.KCEFClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

sealed interface EngineState {
    /** [progress] 0..100, 내려받는 중이 아니면 -1. */
    data class Preparing(val message: String, val progress: Float = -1f) : EngineState
    data class Ready(val client: KCEFClient) : EngineState
    data class Failed(val message: String) : EngineState
    data object RestartRequired : EngineState
}

/**
 * 내장 브라우저(Chromium, JCEF). 네이티브 번들(약 100MB)은 첫 실행 때 앱 데이터 폴더에 한 번만 내려받는다.
 * 로그인·쿠키는 browser-cache 폴더에 남아 다음 실행에도 유지된다.
 */
class BrowserEngine(private val appDir: File, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<EngineState>(EngineState.Preparing("브라우저 준비 중…"))
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            try {
                val bundle = File(appDir, "kcef-bundle")
                KCEF.init(
                    builder = {
                        installDir(bundle)
                        progress {
                            onDownloading { p ->
                                _state.value = EngineState.Preparing("브라우저 구성 요소 내려받는 중… (처음 한 번만)", p.coerceIn(0f, 100f))
                            }
                            onExtracting { _state.value = EngineState.Preparing("브라우저 구성 요소 설치 중…") }
                            onInitializing { _state.value = EngineState.Preparing("브라우저 시작 중…") }
                        }
                        settings {
                            // 기본값은 실행 중인 JDK 폴더를 가리켜 하위 프로세스(GPU·네트워크)가 뜨지 못한다. 번들 경로로 고정한다.
                            browserSubProcessPath = File(bundle, "jcef_helper.exe").absolutePath
                            resourcesDirPath = bundle.absolutePath
                            localesDirPath = File(bundle, "locales").absolutePath
                            cachePath = File(appDir, "browser-cache").absolutePath
                            locale = "ko-KR"
                            persistSessionCookies = true
                        }
                    },
                    onError = { e -> _state.value = EngineState.Failed(e?.message ?: "브라우저를 시작하지 못했습니다") },
                    onRestartRequired = { _state.value = EngineState.RestartRequired },
                )
                val client = KCEF.newClientOrNull { e ->
                    _state.value = EngineState.Failed(e?.message ?: "브라우저를 시작하지 못했습니다")
                }
                if (client != null) {
                    cookieProvider = ::cookieHeader
                    _state.value = EngineState.Ready(client)
                }
            } catch (e: Throwable) {
                _state.value = EngineState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun retry() {
        _state.value = EngineState.Preparing("브라우저 준비 중…")
        started = false
        start()
    }

    fun dispose() {
        cookieProvider = null
        if (_state.value is EngineState.Ready) runCatching { KCEF.disposeBlocking() }
    }

    /** 브라우저에 저장된 쿠키를 Cookie 헤더 형태로. 다운로드 스레드에서 호출한다(최대 0.5초 대기). */
    private fun cookieHeader(url: String): String? {
        val manager = CefCookieManager.getGlobalManager() ?: return null
        val cookies = mutableListOf<CefCookie>()
        val done = CountDownLatch(1)
        val started = manager.visitUrlCookies(url, true) { cookie, count, total, _ ->
            cookies += cookie
            if (count + 1 >= total) done.countDown()
            true
        }
        if (!started) return null
        // 쿠키가 하나도 없으면 방문 콜백이 오지 않는다.
        done.await(500, TimeUnit.MILLISECONDS)
        return cookies.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.name}=${it.value}" }
    }
}
