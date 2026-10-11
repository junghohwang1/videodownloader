package com.example.videodownloader.browser

import com.example.videodownloader.webview2.WebView2Host
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface EngineState {
    data class Preparing(val message: String) : EngineState
    data class Ready(val host: WebView2Host) : EngineState
    data class Failed(val message: String) : EngineState
}

/**
 * 내장 브라우저 엔진. Windows 에 기본으로 깔린 Microsoft Edge WebView2 를 쓴다.
 * 예전 Chromium(JCEF) 과 달리 H.264/AAC 영상도 재생되고, 따로 내려받을 구성 요소가 없다.
 * 로그인·쿠키는 앱 데이터 폴더의 webview2-data 에 남아 다음 실행에도 유지된다.
 */
class BrowserEngine(private val appDir: File, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<EngineState>(EngineState.Preparing("브라우저 준비 중…"))
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        val host = WebView2Host(appDir)
        host.start { error ->
            _state.value = if (error == null) EngineState.Ready(host) else EngineState.Failed(error)
        }
        // 예전 Chromium 엔진이 받아 둔 구성 요소(약 100MB)와 캐시는 더 이상 쓰지 않으므로 지운다.
        scope.launch(Dispatchers.IO) {
            listOf("kcef-bundle", "browser-cache").forEach { runCatching { File(appDir, it).deleteRecursively() } }
        }
    }

    fun retry() {
        _state.value = EngineState.Preparing("브라우저 준비 중…")
        started = false
        start()
    }
}
