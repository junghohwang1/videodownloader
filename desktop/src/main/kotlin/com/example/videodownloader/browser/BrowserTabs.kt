package com.example.videodownloader.browser

import com.example.videodownloader.download.UrlAnalyzer
import com.example.videodownloader.webview2.WebView2Host
import com.example.videodownloader.webview2.WebView2Listener
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.Canvas
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File

/**
 * 브라우저 탭 목록. 탭마다 Edge WebView2 웹뷰가 하나씩 있고, 모두 같은 부모 창([pageArea]) 안에 겹쳐 두고
 * 보고 있는 탭만 보이게 한다. 웹뷰 이벤트는 여기서 받아 해당 탭으로 넘긴다.
 * - 링크의 새 창(target=_blank, window.open)은 새 탭으로 연다. 광고 팝업은 열지 않는다.
 * - 열린 탭(주소)은 앱을 다시 켜도 [stateFile] 에서 복원한다.
 * - [isAdRequest] 가 true 인 요청(광고)은 보내지 않는다.
 * - [earlyScripts] 는 모든 페이지에서 문서가 만들어질 때 먼저 실행되고, [pageScript] 는 페이지를 다 불러온 뒤 실행된다.
 */
class BrowserTabs(
    private val analyzer: UrlAnalyzer,
    private val bookmarks: BookmarkStore,
    private val scope: CoroutineScope,
    private val stateFile: File,
    private val isAdRequest: (url: String) -> Boolean = { false },
    private val pageScript: (url: String) -> String? = { null },
    private val earlyScripts: () -> List<String> = { emptyList() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _tabs = MutableStateFlow<List<BrowserController>>(emptyList())
    val tabs: StateFlow<List<BrowserController>> = _tabs.asStateFlow()

    private val _current = MutableStateFlow<BrowserController?>(null)

    /** 보고 있는 탭. 브라우저 엔진이 준비되기 전에는 null. */
    val current: StateFlow<BrowserController?> = _current.asStateFlow()

    /** 웹페이지를 그리는 자리. 화면(SwingPanel)에 붙이면 모든 탭의 웹뷰가 이 안에 들어간다. */
    val pageArea: Canvas = object : Canvas() {
        override fun addNotify() {
            super.addNotify()
            parentReady()
        }
    }

    private var host: WebView2Host? = null
    private var parent: WinDef.HWND? = null
    private var restored = false
    private var nextId = 1L
    private var startUrl: String? = null
    private val saveLock = Any()

    init {
        pageArea.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = resizeAll()
        })
    }

    /** 브라우저가 준비되면 새 탭으로 열 주소(명령줄 인자). */
    fun openOnStart(url: String) {
        startUrl = url
    }

    /** 브라우저 엔진이 준비되면 부른다. 화면 자리([pageArea])도 준비되면 저장된 탭을 연다. */
    @Synchronized
    fun attach(host: WebView2Host) {
        if (this.host != null) return
        this.host = host
        restoreIfReady()
    }

    @Synchronized
    private fun parentReady() {
        parent = WinDef.HWND(Native.getComponentPointer(pageArea))
        restoreIfReady()
    }

    private fun restoreIfReady() {
        if (restored || host == null || parent == null) return
        restored = true
        val saved = runCatching { json.decodeFromString<SavedTabs>(stateFile.readText()) }.getOrNull()
        saved?.urls?.take(MAX_TABS)?.forEach { newTab(it, select = false) }
        if (_tabs.value.isEmpty()) newTab(null, select = false)
        select((_tabs.value.getOrNull(saved?.current ?: 0) ?: _tabs.value.first()).id)
        startUrl?.let { newTab(it) }
        startUrl = null
    }

    /** 새 탭을 연다. [url] 이 null 이면 홈(북마크) 화면. 탭이 가득 차면 지금 탭에서 연다. */
    @Synchronized
    fun newTab(url: String? = null, select: Boolean = true) {
        val h = host ?: return
        val p = parent ?: return
        if (_tabs.value.size >= MAX_TABS) {
            url?.let { _current.value?.load(it) }
            return
        }
        val tab = BrowserController(nextId++, analyzer, bookmarks, scope)
        tab.create(h, p, url, earlyScripts(), TabEvents(tab))
        _tabs.value = _tabs.value + tab
        resize(tab)
        if (select || _current.value == null) select(tab.id) else tab.setVisible(false)
        save()
    }

    @Synchronized
    fun select(id: Long) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        _current.value = tab
        _tabs.value.forEach { it.setVisible(it === tab) }
        save()
    }

    /** 탭을 닫는다. 마지막 탭을 닫으면 빈 홈 탭을 하나 남긴다. */
    @Synchronized
    fun close(id: Long) {
        val list = _tabs.value
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        val tab = list[index]
        val remaining = list - tab
        _tabs.value = remaining
        tab.dispose()
        when {
            remaining.isEmpty() -> newTab(null)
            _current.value?.id == id -> select((remaining.getOrNull(index) ?: remaining.last()).id)
            else -> save()
        }
    }

    /** 다운로드할 때 보낼 쿠키(웹뷰들이 쿠키 저장소를 함께 쓴다). */
    fun cookieHeader(url: String): String? = _current.value?.cookieHeader(url)

    /** 창이 움직이면 드롭다운 등 웹페이지 팝업 위치를 맞춘다. */
    fun notifyWindowMoved() = _tabs.value.forEach { it.notifyParentMoved() }

    /** WebView2 는 부모 창 크기를 따라가지 않으므로, 자리 크기가 바뀔 때마다 맞춘다. */
    private fun resizeAll() = _tabs.value.forEach(::resize)

    private fun resize(tab: BrowserController) {
        val p = parent ?: return
        val rect = WinDef.RECT()
        User32.INSTANCE.GetClientRect(p, rect)
        tab.setBounds(rect.right - rect.left, rect.bottom - rect.top)
    }

    /** 화면 스레드·WebView2 스레드 어디서 불려도 되도록 탭 목록 잠금과 다른 잠금을 쓴다. */
    fun save() = synchronized(saveLock) {
        val list = _tabs.value
        if (list.isEmpty()) return@synchronized
        val state = SavedTabs(list.map { it.savedUrl() }, list.indexOf(_current.value).coerceAtLeast(0))
        runCatching {
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(json.encodeToString(state))
        }
    }

    fun dispose() {
        save()
        _tabs.value.forEach { it.dispose() }
        _tabs.value = emptyList()
        _current.value = null
    }

    /** 탭 하나의 웹뷰 이벤트. WebView2 스레드에서 불린다. */
    private inner class TabEvents(private val tab: BrowserController) : WebView2Listener {
        /** 지금 불러오는 메인 페이지 주소. 이 문서 요청은 광고 목록에 있어도 막지 않는다(사용자가 직접 연 페이지). */
        @Volatile
        private var navigating: String? = null

        override fun onNavigationStarting(url: String) {
            navigating = url
            tab.onMainFrameLoadStart(url)
        }

        override fun onNavigationCompleted(url: String?, success: Boolean) {
            tab.onMainFrameLoadEnd()
            url?.let(pageScript)?.let { tab.executeScript(it) }
            save()
        }

        override fun onSourceChanged(url: String) = tab.onAddressChange(url)

        override fun onTitleChanged(title: String) = tab.onTitleChange(title)

        override fun onHistoryChanged(canGoBack: Boolean, canGoForward: Boolean) = tab.onNavigationState(canGoBack, canGoForward)

        override fun onFullscreenChanged(fullscreen: Boolean) = tab.onFullscreenChange(fullscreen)

        override fun onNewWindow(url: String, userInitiated: Boolean): Boolean {
            // 광고 팝업이나 사용자가 누르지 않은 창은 열지 않는다.
            if (url.startsWith("http") && userInitiated && !isAdRequest(url)) newTab(url)
            return true
        }

        override fun onResourceRequest(url: String, isDocument: Boolean): Boolean {
            if (isDocument && url == navigating) return false
            if (isAdRequest(url)) return true
            if (!isDocument) tab.onResourceRequest(url)
            return false
        }

        override fun onContextMenu(link: String?, source: String?): List<Pair<String, () -> Unit>> = buildList {
            link?.takeIf { it.startsWith("http") }?.let { add("새 탭에서 링크 열기" to { newTab(it) }) }
            tab.downloadTarget(link, source)?.let { target -> add("이 영상 다운로드" to { tab.requestDownload(target) }) }
        }
    }

    @Serializable
    private data class SavedTabs(val urls: List<String?>, val current: Int = 0)

    private companion object {
        const val MAX_TABS = 20
    }
}
