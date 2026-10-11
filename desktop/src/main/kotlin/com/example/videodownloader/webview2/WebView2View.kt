package com.example.videodownloader.webview2

import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** WebView2 웹뷰(탭 하나)가 알려 주는 일. 모두 WebView2 스레드에서 불린다. */
interface WebView2Listener {
    fun onNavigationStarting(url: String) {}
    fun onNavigationCompleted(url: String?, success: Boolean) {}
    fun onSourceChanged(url: String) {}
    fun onTitleChanged(title: String) {}
    fun onHistoryChanged(canGoBack: Boolean, canGoForward: Boolean) {}
    fun onFullscreenChanged(fullscreen: Boolean) {}

    /** 새 창(target=_blank, window.open). 직접 처리했으면(새 탭으로 열었거나 막았으면) true. */
    fun onNewWindow(url: String, userInitiated: Boolean): Boolean = false

    /** 페이지가 보내는 하위 요청. true 를 돌려주면 막는다. [isDocument] 는 페이지·iframe 문서 요청. */
    fun onResourceRequest(url: String, isDocument: Boolean): Boolean = false

    /** 페이지 스크립트가 chrome.webview.postMessage(문자열) 로 보낸 메시지. */
    fun onWebMessage(message: String) {}

    /** 우클릭 메뉴 끝에 붙일 항목(이름 → 눌렀을 때 할 일). [link] 는 링크 주소, [source] 는 영상·이미지 주소. */
    fun onContextMenu(link: String?, source: String?): List<Pair<String, () -> Unit>> = emptyList()
}

@Structure.FieldOrder("left", "top", "right", "bottom")
internal class RectByValue(
    @JvmField var left: Int = 0,
    @JvmField var top: Int = 0,
    @JvmField var right: Int = 0,
    @JvmField var bottom: Int = 0,
) : Structure(), Structure.ByValue

/**
 * Edge WebView2 웹뷰 하나. 부모 창(HWND) 안에 붙어 그려진다. 모든 함수는 어느 스레드에서 불러도 되고,
 * 실제 처리는 [host] 의 WebView2 스레드에서 한다.
 */
class WebView2View(
    private val host: WebView2Host,
    private val listener: WebView2Listener,
) {
    private var controller: ComPtr? = null
    private var webview: ComPtr? = null
    private val handlers = mutableListOf<ComHandler>()
    private val pending = mutableListOf<() -> Unit>()

    @Volatile
    var isReady = false
        private set

    @Volatile
    private var closed = false

    // 웹뷰가 만들어지기 전에 요청받은 표시 여부·크기. 준비되면 적용한다(탭 복원 때 모든 탭이 겹쳐 보이지 않게).
    @Volatile
    private var wantVisible = true

    @Volatile
    private var wantWidth = 0

    @Volatile
    private var wantHeight = 0

    /** 부모 창 [parent] 에 웹뷰를 만들고, 준비되면 [startUrl] 을 연다. [scripts] 는 문서가 만들어질 때마다 먼저 실행된다. */
    fun create(parent: WinDef.HWND, startUrl: String?, scripts: List<String>, onReady: () -> Unit = {}) = host.post {
        val env = host.environment ?: return@post
        lateinit var handler: ComHandler
        handler = ComHandler { result, ctrl ->
            handler.release()
            if (closed || ComHandler.hresult(result) < 0 || ctrl == null) return@ComHandler
            val c = ComPtr(ctrl).also { it.addRef() }
            controller = c
            c.call(CONTROLLER_PUT_BOUNDS, RectByValue(0, 0, wantWidth, wantHeight))
            c.call(CONTROLLER_PUT_IS_VISIBLE, if (wantVisible) 1 else 0)
            val wv = c.getObject(CONTROLLER_GET_CORE_WEBVIEW2) ?: return@ComHandler
            webview = wv
            setup(wv, scripts)
            isReady = true
            startUrl?.let { wv.call(WV_NAVIGATE, wstr(it)) }
            pending.forEach { it() }
            pending.clear()
            onReady()
        }
        env.check(ENV_CREATE_CONTROLLER, parent.pointer, handler.pointer)
    }

    private fun setup(wv: ComPtr, scripts: List<String>) {
        wv.getObject(WV_GET_SETTINGS)?.let { settings ->
            settings.call(SETTINGS_PUT_STATUS_BAR, 0)
            settings.call(SETTINGS_PUT_DEVTOOLS, 0)
            settings.release()
        }
        scripts.forEach { script ->
            lateinit var done: ComHandler
            done = ComHandler { _, _ -> done.release() }
            wv.call(WV_ADD_SCRIPT_ON_DOCUMENT_CREATED, wstr(script), done.pointer)
        }

        on(wv, WV_ADD_NAVIGATION_STARTING) { _, args ->
            val a = ComPtr(args ?: return@on)
            a.getString(NAV_STARTING_GET_URI)?.let(listener::onNavigationStarting)
        }
        on(wv, WV_ADD_NAVIGATION_COMPLETED) { _, args ->
            val ok = args?.let { ComPtr(it).getBool(NAV_COMPLETED_GET_IS_SUCCESS) } ?: false
            listener.onNavigationCompleted(wv.getString(WV_GET_SOURCE), ok)
            notifyHistory(wv)
        }
        on(wv, WV_ADD_SOURCE_CHANGED) { _, _ ->
            wv.getString(WV_GET_SOURCE)?.let(listener::onSourceChanged)
        }
        on(wv, WV_ADD_HISTORY_CHANGED) { _, _ -> notifyHistory(wv) }
        on(wv, WV_ADD_DOCUMENT_TITLE_CHANGED) { _, _ ->
            listener.onTitleChanged(wv.getString(WV_GET_DOCUMENT_TITLE).orEmpty())
        }
        on(wv, WV_ADD_FULLSCREEN_CHANGED) { _, _ ->
            listener.onFullscreenChanged(wv.getBool(WV_GET_CONTAINS_FULLSCREEN))
        }
        on(wv, WV_ADD_NEW_WINDOW_REQUESTED) { _, args ->
            val a = ComPtr(args ?: return@on)
            val url = a.getString(NEW_WINDOW_GET_URI) ?: return@on
            val user = a.getBool(NEW_WINDOW_GET_IS_USER_INITIATED)
            if (listener.onNewWindow(url, user)) a.call(NEW_WINDOW_PUT_HANDLED, 1)
        }
        on(wv, WV_ADD_WEB_MESSAGE_RECEIVED) { _, args ->
            val a = ComPtr(args ?: return@on)
            a.getString(WEB_MESSAGE_TRY_GET_STRING)?.let(listener::onWebMessage)
        }
        wv.queryInterface(IID_ICOREWEBVIEW2_11)?.let { wv11 ->
            on(wv11, WV11_ADD_CONTEXT_MENU_REQUESTED) { _, args -> addContextMenuItems(ComPtr(args ?: return@on)) }
            wv11.release()
        }
        wv.call(WV_ADD_WEB_RESOURCE_REQUESTED_FILTER, wstr("*"), RESOURCE_CONTEXT_ALL)
        on(wv, WV_ADD_WEB_RESOURCE_REQUESTED) { _, args ->
            val a = ComPtr(args ?: return@on)
            val request = a.getObject(RESOURCE_ARGS_GET_REQUEST) ?: return@on
            val url = request.getString(REQUEST_GET_URI)
            request.release()
            val context = IntByReference()
            a.call(RESOURCE_ARGS_GET_CONTEXT, context)
            if (url != null && listener.onResourceRequest(url, context.value == RESOURCE_CONTEXT_DOCUMENT)) {
                blockedResponse()?.let { response ->
                    a.call(RESOURCE_ARGS_PUT_RESPONSE, response.pointer)
                    response.release()
                }
            }
        }
    }

    /** 이전 메뉴에서 만든 항목 처리기. 새 메뉴를 열 때 정리한다. */
    private val menuHandlers = mutableListOf<ComHandler>()

    private fun addContextMenuItems(args: ComPtr) {
        menuHandlers.forEach { it.release() }
        menuHandlers.clear()
        val target = args.getObject(MENU_ARGS_GET_TARGET) ?: return
        val link = if (target.getBool(TARGET_HAS_LINK)) target.getString(TARGET_GET_LINK) else null
        val source = if (target.getBool(TARGET_HAS_SOURCE)) target.getString(TARGET_GET_SOURCE) else null
        target.release()
        val items = listener.onContextMenu(link, source)
        if (items.isEmpty()) return
        val env9 = host.environment?.queryInterface(IID_ENVIRONMENT9) ?: return
        val collection = args.getObject(MENU_ARGS_GET_ITEMS)
        if (collection == null) {
            env9.release()
            return
        }
        fun append(label: String, kind: Int, action: (() -> Unit)?) {
            val out = PointerByReference()
            if (env9.call(ENV9_CREATE_CONTEXT_MENU_ITEM, wstr(label), null, kind, out) < 0) return
            val item = ComPtr(out.value ?: return)
            if (action != null) {
                val handler = ComHandler { _, _ -> action() }
                menuHandlers += handler
                item.call(MENU_ITEM_ADD_CUSTOM_SELECTED, handler.pointer, eventToken())
            }
            val count = IntByReference()
            collection.call(MENU_COLLECTION_GET_COUNT, count)
            collection.call(MENU_COLLECTION_INSERT_AT, count.value, item.pointer)
            item.release()
        }
        append("", MENU_KIND_SEPARATOR, null)
        items.forEach { (label, action) -> append(label, MENU_KIND_COMMAND, action) }
        collection.release()
        env9.release()
    }

    /** 막은 요청에 돌려줄 빈 응답(403). */
    private fun blockedResponse(): ComPtr? {
        val env = host.environment ?: return null
        val out = PointerByReference()
        val hr = env.call(ENV_CREATE_WEB_RESOURCE_RESPONSE, null, 403, wstr("Blocked"), wstr(""), out)
        return if (hr < 0) null else out.value?.let(::ComPtr)
    }

    private fun notifyHistory(wv: ComPtr) {
        listener.onHistoryChanged(wv.getBool(WV_GET_CAN_GO_BACK), wv.getBool(WV_GET_CAN_GO_FORWARD))
    }

    private fun on(wv: ComPtr, addIndex: Int, block: (sender: Pointer?, args: Pointer?) -> Unit) {
        val handler = ComHandler(block)
        handlers += handler
        wv.call(addIndex, handler.pointer, eventToken())
    }

    /** 준비되기 전에 부른 동작은 준비된 뒤에 실행한다. */
    private fun whenReady(action: (ComPtr) -> Unit) = host.post {
        if (closed) return@post
        val wv = webview
        if (wv != null) action(wv) else pending += { webview?.let(action) }
    }

    fun navigate(url: String) = whenReady { it.call(WV_NAVIGATE, wstr(url)) }
    fun goBack() = whenReady { it.call(WV_GO_BACK) }
    fun goForward() = whenReady { it.call(WV_GO_FORWARD) }
    fun reload() = whenReady { it.call(WV_RELOAD) }
    fun stop() = whenReady { it.call(WV_STOP) }

    /** 스크립트를 실행하고 결과(JSON 문자열, 예: 문자열 결과면 따옴표로 감싼 값)를 [onResult] 로 돌려준다. */
    fun executeScript(script: String, onResult: (json: String?) -> Unit = {}) = whenReady { wv ->
        lateinit var handler: ComHandler
        handler = ComHandler { result, json ->
            handler.release()
            onResult(if (ComHandler.hresult(result) < 0) null else json?.getWideString(0))
        }
        wv.call(WV_EXECUTE_SCRIPT, wstr(script), handler.pointer)
    }

    /** [executeScript] 를 기다려 결과를 받는다(WebView2 스레드에서 부르면 안 된다). */
    fun executeScriptBlocking(script: String, timeoutMs: Long = 3_000): String? {
        val latch = CountDownLatch(1)
        var value: String? = null
        executeScript(script) { value = it; latch.countDown() }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return value
    }

    /** 웹뷰 크기를 부모 창의 [width]x[height](물리 픽셀)로 맞춘다. */
    fun setBounds(width: Int, height: Int) {
        wantWidth = width
        wantHeight = height
        host.post { controller?.call(CONTROLLER_PUT_BOUNDS, RectByValue(0, 0, width, height)) }
    }

    fun setVisible(visible: Boolean) {
        wantVisible = visible
        host.post {
            controller?.call(CONTROLLER_PUT_IS_VISIBLE, if (visible) 1 else 0)
            if (visible) controller?.call(CONTROLLER_MOVE_FOCUS, 0)
        }
    }

    /** 창이 움직였을 때 드롭다운 등 팝업 위치를 맞춘다. */
    fun notifyParentMoved() = host.post { controller?.call(CONTROLLER_NOTIFY_PARENT_MOVED) }

    /** [url] 에 보낼 쿠키를 Cookie 헤더 형태로(다운로드용). 최대 [timeoutMs] 기다린다. */
    fun cookieHeader(url: String, timeoutMs: Long = 1_000): String? {
        val latch = CountDownLatch(1)
        var header: String? = null
        whenReady { wv ->
            val wv2 = wv.queryInterface(IID_ICOREWEBVIEW2_2) ?: return@whenReady latch.countDown()
            val manager = wv2.getObject(WV2_GET_COOKIE_MANAGER)
            wv2.release()
            if (manager == null) return@whenReady latch.countDown()
            lateinit var handler: ComHandler
            handler = ComHandler { result, list ->
                handler.release()
                try {
                    if (ComHandler.hresult(result) >= 0 && list != null) {
                        val cookies = ComPtr(list)
                        val count = IntByReference()
                        cookies.call(COOKIE_LIST_GET_COUNT, count)
                        header = (0 until count.value).mapNotNull { i ->
                            val out = PointerByReference()
                            if (cookies.call(COOKIE_LIST_GET_VALUE_AT, i, out) < 0) return@mapNotNull null
                            val cookie = ComPtr(out.value ?: return@mapNotNull null)
                            val pair = "${cookie.getString(COOKIE_GET_NAME)}=${cookie.getString(COOKIE_GET_VALUE)}"
                            cookie.release()
                            pair
                        }.takeIf { it.isNotEmpty() }?.joinToString("; ")
                    }
                } finally {
                    manager.release()
                    latch.countDown()
                }
            }
            manager.call(COOKIE_MANAGER_GET_COOKIES, wstr(url), handler.pointer)
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return header
    }

    fun close() = host.post {
        closed = true
        controller?.call(CONTROLLER_CLOSE)
        webview?.release()
        controller?.release()
        webview = null
        controller = null
        handlers.forEach { it.release() }
        handlers.clear()
    }

    private companion object {
        // ICoreWebView2Environment
        const val ENV_CREATE_CONTROLLER = 3
        const val ENV_CREATE_WEB_RESOURCE_RESPONSE = 4

        // ICoreWebView2Controller
        const val CONTROLLER_PUT_IS_VISIBLE = 4
        const val CONTROLLER_PUT_BOUNDS = 6
        const val CONTROLLER_MOVE_FOCUS = 12
        const val CONTROLLER_NOTIFY_PARENT_MOVED = 23
        const val CONTROLLER_CLOSE = 24
        const val CONTROLLER_GET_CORE_WEBVIEW2 = 25

        // ICoreWebView2
        const val WV_GET_SETTINGS = 3
        const val WV_GET_SOURCE = 4
        const val WV_NAVIGATE = 5
        const val WV_ADD_NAVIGATION_STARTING = 7
        const val WV_ADD_SOURCE_CHANGED = 11
        const val WV_ADD_HISTORY_CHANGED = 13
        const val WV_ADD_NAVIGATION_COMPLETED = 15
        const val WV_ADD_SCRIPT_ON_DOCUMENT_CREATED = 27
        const val WV_EXECUTE_SCRIPT = 29
        const val WV_RELOAD = 31
        const val WV_ADD_WEB_MESSAGE_RECEIVED = 34
        const val WV_GET_CAN_GO_BACK = 38
        const val WV_GET_CAN_GO_FORWARD = 39
        const val WV_GO_BACK = 40
        const val WV_GO_FORWARD = 41
        const val WV_STOP = 43
        const val WV_ADD_NEW_WINDOW_REQUESTED = 44
        const val WV_ADD_DOCUMENT_TITLE_CHANGED = 46
        const val WV_GET_DOCUMENT_TITLE = 48
        const val WV_ADD_FULLSCREEN_CHANGED = 52
        const val WV_GET_CONTAINS_FULLSCREEN = 54
        const val WV_ADD_WEB_RESOURCE_REQUESTED = 55
        const val WV_ADD_WEB_RESOURCE_REQUESTED_FILTER = 57

        // ICoreWebView2_2
        const val IID_ICOREWEBVIEW2_2 = "9E8F0CF8-E670-4B5E-B2BC-73E061E3184C"
        const val WV2_GET_COOKIE_MANAGER = 66

        // 우클릭 메뉴(ICoreWebView2_11, ICoreWebView2Environment9)
        const val IID_ICOREWEBVIEW2_11 = "0be78e56-c193-4051-b943-23b460c08bdb"
        const val WV11_ADD_CONTEXT_MENU_REQUESTED = 100
        const val IID_ENVIRONMENT9 = "f06f41bf-4b5a-49d8-b9f6-fa16cd29f274"
        const val ENV9_CREATE_CONTEXT_MENU_ITEM = 19
        const val MENU_ARGS_GET_ITEMS = 3
        const val MENU_ARGS_GET_TARGET = 4
        const val TARGET_HAS_LINK = 8
        const val TARGET_GET_LINK = 9
        const val TARGET_HAS_SOURCE = 12
        const val TARGET_GET_SOURCE = 13
        const val MENU_COLLECTION_GET_COUNT = 3
        const val MENU_COLLECTION_INSERT_AT = 6
        const val MENU_ITEM_ADD_CUSTOM_SELECTED = 14
        const val MENU_KIND_COMMAND = 0
        const val MENU_KIND_SEPARATOR = 3

        // ICoreWebView2Settings
        const val SETTINGS_PUT_STATUS_BAR = 10
        const val SETTINGS_PUT_DEVTOOLS = 12

        // 이벤트 인자
        const val NAV_STARTING_GET_URI = 3
        const val NAV_COMPLETED_GET_IS_SUCCESS = 3
        const val NEW_WINDOW_GET_URI = 3
        const val NEW_WINDOW_PUT_HANDLED = 6
        const val NEW_WINDOW_GET_IS_USER_INITIATED = 8
        const val WEB_MESSAGE_TRY_GET_STRING = 5
        const val RESOURCE_ARGS_GET_REQUEST = 3
        const val RESOURCE_ARGS_PUT_RESPONSE = 5
        const val RESOURCE_ARGS_GET_CONTEXT = 7
        const val REQUEST_GET_URI = 3
        const val RESOURCE_CONTEXT_ALL = 0
        const val RESOURCE_CONTEXT_DOCUMENT = 1

        // 쿠키
        const val COOKIE_MANAGER_GET_COOKIES = 5
        const val COOKIE_LIST_GET_COUNT = 3
        const val COOKIE_LIST_GET_VALUE_AT = 4
        const val COOKIE_GET_NAME = 3
        const val COOKIE_GET_VALUE = 4
    }
}
