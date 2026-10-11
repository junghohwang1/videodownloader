package com.example.videodownloader.webview2

import com.sun.jna.NativeLibrary
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WebView2 전용 스레드. WebView2 는 만든 스레드(STA)에서만 다뤄야 하고, 결과·이벤트도 그 스레드의 메시지 루프로 온다.
 * 그래서 메시지 루프를 도는 스레드를 하나 두고, 모든 WebView2 호출을 [post] 로 이 스레드에 넘긴다.
 *
 * 브라우저 엔진은 Windows 에 기본으로 깔린 Microsoft Edge WebView2 런타임을 쓴다(H.264/AAC 재생 가능).
 * 환경 만들기에 필요한 WebView2Loader.dll(약 160KB, Microsoft BSD 라이선스)은 앱에 넣어 두고 [appDir] 로 꺼내 쓴다.
 */
class WebView2Host(private val appDir: File) {

    private val tasks = ConcurrentLinkedQueue<() -> Unit>()
    private val started = CountDownLatch(1)

    @Volatile
    private var threadId = 0

    internal var environment: ComPtr? = null
        private set

    private val thread = Thread({ loop() }, "webview2").apply { isDaemon = true }

    /**
     * 스레드를 띄우고 WebView2 환경을 만든다. 결과는 [onResult] 로 알린다(실패면 오류 메시지).
     * 사용자 데이터(쿠키·캐시)는 [appDir]\webview2-data 에 둔다.
     */
    fun start(onResult: (error: String?) -> Unit) {
        thread.start()
        started.await(10, TimeUnit.SECONDS)
        post {
            try {
                val loader = NativeLibrary.getInstance(extractLoader().absolutePath)
                val create = loader.getFunction("CreateCoreWebView2EnvironmentWithOptions")
                lateinit var handler: ComHandler
                handler = ComHandler { result, env ->
                    handler.release()
                    val hr = ComHandler.hresult(result)
                    if (hr < 0 || env == null) {
                        onResult(environmentError(hr))
                    } else {
                        environment = ComPtr(env).also { it.addRef() }
                        onResult(null)
                    }
                }
                val dataDir = File(appDir, "webview2-data").apply { mkdirs() }
                val hr = create.invokeInt(arrayOf(null, wstr(dataDir.absolutePath), null, handler.pointer))
                if (hr < 0) {
                    handler.release()
                    onResult(environmentError(hr))
                }
            } catch (e: Throwable) {
                onResult("브라우저(WebView2)를 시작하지 못했습니다: ${e.message}")
            }
        }
    }

    /** WebView2 스레드에서 실행한다. */
    fun post(task: () -> Unit) {
        tasks.add(task)
        if (threadId != 0) User32.INSTANCE.PostThreadMessage(threadId, WM_RUN, WinDef.WPARAM(0), WinDef.LPARAM(0))
    }

    private fun loop() {
        Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED)
        val msg = WinUser.MSG()
        // 메시지 큐를 만들어 둔다(PostThreadMessage 를 받으려면 필요).
        User32.INSTANCE.PeekMessage(msg, null, 0, 0, 0)
        threadId = Kernel32.INSTANCE.GetCurrentThreadId()
        started.countDown()
        runTasks()
        while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
            if (msg.message != WM_RUN) {
                User32.INSTANCE.TranslateMessage(msg)
                User32.INSTANCE.DispatchMessage(msg)
            }
            runTasks()
        }
    }

    private fun runTasks() {
        while (true) {
            val task = tasks.poll() ?: return
            try {
                task()
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    private fun extractLoader(): File {
        val target = File(appDir, "webview2/WebView2Loader.dll")
        val resource = javaClass.getResourceAsStream("/webview2/WebView2Loader.dll")
            ?: throw IllegalStateException("WebView2Loader.dll 이 앱에 없습니다")
        val bytes = resource.use { it.readBytes() }
        if (!target.isFile || target.length() != bytes.size.toLong()) {
            target.parentFile.mkdirs()
            target.writeBytes(bytes)
        }
        return target
    }

    private fun environmentError(hr: Int): String =
        if (hr == HRESULT_FILE_NOT_FOUND) {
            "Microsoft Edge WebView2 런타임이 없습니다. https://go.microsoft.com/fwlink/p/?LinkId=2124703 에서 설치한 뒤 앱을 다시 시작해 주세요."
        } else {
            "브라우저(WebView2)를 시작하지 못했습니다(0x%08X).".format(hr)
        }

    internal companion object {
        const val WM_RUN = WinUser.WM_USER + 0x200
        const val HRESULT_FILE_NOT_FOUND = 0x80070002.toInt()
    }
}
