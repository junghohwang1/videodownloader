package com.example.videodownloader.browser

import com.example.videodownloader.download.UrlAnalyzer
import dev.datlag.kcef.KCEFClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefMenuModel
import org.cef.handler.CefContextMenuHandlerAdapter
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.io.File

/**
 * 브라우저 탭 목록. CEF 클라이언트는 하나라 이벤트를 여기서 받아, 어느 탭(브라우저 ID) 것인지 보고 넘긴다.
 * - 링크의 새 창(target=_blank, window.open)은 새 탭으로 연다.
 * - 열린 탭(주소)은 앱을 다시 켜도 [stateFile] 에서 복원한다.
 * - [isAdRequest] 가 true 인 요청(광고)은 보내지 않는다.
 */
class BrowserTabs(
    private val analyzer: UrlAnalyzer,
    private val bookmarks: BookmarkStore,
    private val scope: CoroutineScope,
    private val stateFile: File,
    private val isAdRequest: (url: String) -> Boolean = { false },
    private val pageScript: (url: String) -> String? = { null },
    private val earlyScript: (url: String) -> String? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _tabs = MutableStateFlow<List<BrowserController>>(emptyList())
    val tabs: StateFlow<List<BrowserController>> = _tabs.asStateFlow()

    private val _current = MutableStateFlow<BrowserController?>(null)

    /** 보고 있는 탭. 브라우저 엔진이 준비되기 전에는 null. */
    val current: StateFlow<BrowserController?> = _current.asStateFlow()

    private var client: KCEFClient? = null
    private var nextId = 1L
    private var startUrl: String? = null

    /** 브라우저가 준비되면 새 탭으로 열 주소(명령줄 인자). */
    fun openOnStart(url: String) {
        startUrl = url
    }

    @Synchronized
    fun attach(client: KCEFClient) {
        if (this.client != null) return
        this.client = client
        client.addDisplayHandler(displayHandler)
        client.addLoadHandler(loadHandler)
        client.addRequestHandler(requestHandler)
        client.addLifeSpanHandler(lifeSpanHandler)
        client.addContextMenuHandler(contextMenuHandler)

        val saved = runCatching { json.decodeFromString<SavedTabs>(stateFile.readText()) }.getOrNull()
        saved?.urls?.take(MAX_TABS)?.forEach { newTab(it, select = false) }
        if (_tabs.value.isEmpty()) newTab(null, select = false)
        _current.value = _tabs.value.getOrNull(saved?.current ?: 0) ?: _tabs.value.first()
        startUrl?.let { newTab(it) }
        startUrl = null
    }

    /** 새 탭을 연다. [url] 이 null 이면 홈(북마크) 화면. 탭이 가득 차면 지금 탭에서 연다. */
    @Synchronized
    fun newTab(url: String? = null, select: Boolean = true) {
        val c = client ?: return
        if (_tabs.value.size >= MAX_TABS) {
            url?.let { _current.value?.load(it) }
            return
        }
        val tab = BrowserController(nextId++, analyzer, bookmarks, scope)
        tab.create(c, url)
        _tabs.value = _tabs.value + tab
        if (select || _current.value == null) _current.value = tab
        save()
    }

    @Synchronized
    fun select(id: Long) {
        _tabs.value.firstOrNull { it.id == id }?.let { _current.value = it }
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
        if (_current.value?.id == id) _current.value = remaining.getOrNull(index) ?: remaining.lastOrNull()
        tab.dispose()
        if (remaining.isEmpty()) newTab(null) else save()
    }

    /** CEF 스레드에서도 불리므로 탭 목록 잠금과 다른 잠금을 쓴다(같은 잠금이면 탭을 만드는 중에 교착될 수 있다). */
    fun save() = synchronized(saveLock) {
        val list = _tabs.value
        if (list.isEmpty()) return@synchronized
        val state = SavedTabs(list.map { it.savedUrl() }, list.indexOf(_current.value).coerceAtLeast(0))
        runCatching {
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(json.encodeToString(state))
        }
    }

    private val saveLock = Any()

    fun dispose() {
        save()
        _tabs.value.forEach { it.dispose() }
        _tabs.value = emptyList()
        _current.value = null
    }

    private fun tabFor(browser: CefBrowser?): BrowserController? {
        val id = browser?.identifier ?: return null
        return _tabs.value.firstOrNull { it.browserIdentifier == id }
    }

    // ---- CEF 핸들러 ----

    private val displayHandler = object : CefDisplayHandlerAdapter() {
        override fun onAddressChange(browser: CefBrowser?, frame: CefFrame?, url: String?) {
            if (frame != null && !frame.isMain) return
            tabFor(browser)?.onAddressChange(url ?: return)
        }

        override fun onTitleChange(browser: CefBrowser?, title: String?) {
            tabFor(browser)?.onTitleChange(title)
        }

        override fun onFullscreenModeChange(browser: CefBrowser?, fullscreen: Boolean) {
            tabFor(browser)?.onFullscreenChange(fullscreen)
        }
    }

    private val loadHandler = object : CefLoadHandlerAdapter() {
        override fun onLoadingStateChange(browser: CefBrowser?, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean) {
            // isLoading 은 광고 iframe 같은 하위 프레임 로딩에도 바뀐다. 로딩 표시는 메인 프레임 기준(onLoadStart/End)으로 한다.
            tabFor(browser)?.onNavigationState(canGoBack, canGoForward)
        }

        override fun onLoadStart(browser: CefBrowser?, frame: CefFrame?, transitionType: CefRequest.TransitionType?) {
            if (frame?.isMain != true) return
            // 문서가 만들어진 직후, 페이지 스크립트보다 먼저 실행되도록 여기서 넣는다.
            frame.url?.let(earlyScript)?.let { frame.executeJavaScript(it, frame.url, 0) }
            tabFor(browser)?.onMainFrameLoadStart(frame.url)
        }

        override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
            if (frame?.isMain != true) return
            tabFor(browser)?.onMainFrameLoadEnd()
            frame.url?.let(pageScript)?.let { frame.executeJavaScript(it, frame.url, 0) }
            // 강제 종료돼도 열린 탭 주소가 남도록 페이지를 열 때마다 저장한다.
            save()
        }

        override fun onLoadError(
            browser: CefBrowser?,
            frame: CefFrame?,
            errorCode: CefLoadHandler.ErrorCode?,
            errorText: String?,
            failedUrl: String?,
        ) {
            if (frame?.isMain == true) tabFor(browser)?.onMainFrameLoadEnd()
        }
    }

    /** 광고 요청은 보내기 전에 취소한다. */
    private val blockingHandler = object : CefResourceRequestHandlerAdapter() {
        override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): Boolean =
            request?.url?.let(isAdRequest) == true
    }

    private val requestHandler = object : CefRequestHandlerAdapter() {
        override fun getResourceRequestHandler(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest?,
            isNavigation: Boolean,
            isDownload: Boolean,
            requestInitiator: String?,
            disableDefaultHandling: BoolRef?,
        ): CefResourceRequestHandler? {
            val url = request?.url ?: return null
            // 메인 페이지 이동은 막지 않는다(사용자가 직접 연 주소).
            val mainNavigation = isNavigation && frame?.isMain == true
            if (!mainNavigation && isAdRequest(url)) return blockingHandler
            if (!isNavigation) tabFor(browser)?.onResourceRequest(url)
            return null
        }
    }

    private val lifeSpanHandler = object : CefLifeSpanHandlerAdapter() {
        /** 링크의 새 창(target=_blank, window.open)은 새 탭으로 연다. 광고 팝업은 열지 않는다. */
        override fun onBeforePopup(browser: CefBrowser?, frame: CefFrame?, targetUrl: String?, targetFrameName: String?): Boolean {
            val url = targetUrl?.takeIf { it.startsWith("http") } ?: return true
            if (!isAdRequest(url)) onUiThread { newTab(url) }
            return true
        }
    }

    private val contextMenuHandler = object : CefContextMenuHandlerAdapter() {
        override fun onBeforeContextMenu(browser: CefBrowser?, frame: CefFrame?, params: CefContextMenuParams?, model: CefMenuModel?) {
            model ?: return
            val tab = tabFor(browser) ?: return
            val link = params?.linkUrl?.takeIf { it.startsWith("http") }
            if (link != null || tab.canDownload(params)) {
                if (model.count > 0) model.addSeparator()
            }
            if (link != null) model.addItem(MENU_NEW_TAB, "새 탭에서 링크 열기")
            if (tab.canDownload(params)) model.addItem(MENU_DOWNLOAD, "이 영상 다운로드")
        }

        override fun onContextMenuCommand(
            browser: CefBrowser?,
            frame: CefFrame?,
            params: CefContextMenuParams?,
            commandId: Int,
            eventFlags: Int,
        ): Boolean = when (commandId) {
            MENU_DOWNLOAD -> {
                tabFor(browser)?.downloadFromContextMenu(params)
                true
            }
            MENU_NEW_TAB -> {
                params?.linkUrl?.let { url -> onUiThread { newTab(url) } }
                true
            }
            else -> false
        }
    }

    @Serializable
    private data class SavedTabs(val urls: List<String?>, val current: Int = 0)

    private companion object {
        const val MAX_TABS = 20
        const val MENU_DOWNLOAD = 26500 // MENU_ID_USER_FIRST
        const val MENU_NEW_TAB = 26501
    }
}

/** CEF 콜백 스레드에서 바로 브라우저를 만들면 교착될 수 있어 UI 스레드로 넘긴다. */
private fun onUiThread(block: () -> Unit) {
    javax.swing.SwingUtilities.invokeLater(block)
}
