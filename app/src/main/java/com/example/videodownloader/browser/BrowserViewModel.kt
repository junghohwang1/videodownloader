package com.example.videodownloader.browser

import android.util.Patterns
import android.webkit.URLUtil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.videodownloader.AppContainer
import com.example.videodownloader.data.db.BookmarkEntity
import com.example.videodownloader.data.db.HistoryEntity
import com.example.videodownloader.data.db.MediaKind
import com.example.videodownloader.data.settings.AppSettings
import com.example.videodownloader.download.NewDownload
import com.example.videodownloader.youtube.YouTubeSupport
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

data class BrowserUiState(
    val url: String = "",
    val title: String = "",
    val progress: Int = 100,
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val showHome: Boolean = true,
    val hasPage: Boolean = false,
)

/** 탭 목록 화면에 보여줄 요약 정보. */
data class TabInfo(val id: Long, val title: String, val url: String, val showHome: Boolean, val detectedCount: Int)

sealed interface BrowserEvent {
    data class DownloadQueued(val fileName: String) : BrowserEvent
    data class Message(val text: String) : BrowserEvent
    data class OfferTranslation(val tabId: Long, val url: String, val languageName: String?) : BrowserEvent
}

/** 탭 하나의 상태. WebView 콜백(일부는 백그라운드 스레드)이 직접 갱신한다. */
class TabSession(val id: Long, showHome: Boolean, restored: SavedTab? = null) {
    // 홈 화면을 보던 탭은 홈으로, 페이지를 보던 탭은 그 페이지로 복원한다.
    private val restoredPage = restored?.takeIf { !it.showHome && it.url.isNotEmpty() }

    val ui = MutableStateFlow(
        if (restoredPage != null) {
            BrowserUiState(url = restoredPage.url, title = restoredPage.title, showHome = false, hasPage = true)
        } else {
            BrowserUiState(showHome = showHome || restored != null, hasPage = !showHome && restored == null)
        },
    )

    /** 앱을 다시 켠 뒤 이 탭의 WebView 를 처음 만들 때 불러올 주소. */
    @Volatile
    var restoreUrl: String? = restoredPage?.url
    val detectedAll = MutableStateFlow<List<DetectedMedia>>(emptyList())
    val hiddenUrls = MutableStateFlow<Set<String>>(emptySet())

    @Volatile
    var pageUrl: String? = null

    @Volatile
    var title: String? = null

    /** 지금 정보를 띄운 유튜브 영상 ID(같은 영상을 다시 조회하지 않기 위해). */
    @Volatile
    var youtubeId: String? = null

    /** 마스터 플레이리스트의 하위 화질은 숨긴다. */
    val detected = combine(detectedAll, hiddenUrls) { list, hidden -> list.filter { it.url !in hidden } }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class BrowserViewModel(private val container: AppContainer) : ViewModel() {

    private val historyDao = container.database.historyDao()
    private val bookmarkDao = container.database.bookmarkDao()
    private val tabStore = container.tabStore
    private val savedTabs = tabStore.load()
    private val nextTabId = AtomicLong((savedTabs?.tabs?.maxOf { it.id } ?: 0L) + 1)

    // 지난번에 열려 있던 탭을 복원한다(페이지는 그 탭을 처음 볼 때 불러온다).
    private val _tabs = MutableStateFlow(
        savedTabs?.tabs?.map { TabSession(it.id, it.showHome, restored = it) }
            ?: listOf(TabSession(nextTabId.getAndIncrement(), showHome = true)),
    )
    private val _currentTabId = MutableStateFlow(
        savedTabs?.currentTabId?.takeIf { id -> _tabs.value.any { it.id == id } } ?: _tabs.value.first().id,
    )
    val currentTabId: StateFlow<Long> = _currentTabId.asStateFlow()

    /** 열린 탭 ID 목록(WebView 생성·정리에 쓴다). */
    val tabIds: StateFlow<List<Long>> =
        _tabs.map { tabs -> tabs.map { it.id } }.stateIn(viewModelScope, SharingStarted.Eagerly, _tabs.value.map { it.id })

    val tabInfos: StateFlow<List<TabInfo>> = _tabs
        .flatMapLatest { tabs ->
            if (tabs.isEmpty()) flowOf(emptyList())
            else combine(tabs.map { tab -> combine(tab.ui, tab.detected) { ui, detected -> tab.id to (ui to detected.size) } }) { rows ->
                rows.map { (id, pair) ->
                    val (ui, count) = pair
                    TabInfo(id, ui.title, ui.url, ui.showHome, count)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val currentTab = combine(_tabs, _currentTabId) { tabs, id -> tabs.firstOrNull { it.id == id } ?: tabs.first() }

    val ui: StateFlow<BrowserUiState> = currentTab.flatMapLatest { it.ui }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BrowserUiState())

    /** 현재 탭에서 감지한 미디어. */
    val detected: StateFlow<List<DetectedMedia>> = currentTab.flatMapLatest { it.detected }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** WebView 의 다운로드 요청 등으로 바로 다운로드 대화상자를 띄울 항목. */
    private val _pendingDownload = MutableStateFlow<DetectedMedia?>(null)
    val pendingDownload: StateFlow<DetectedMedia?> = _pendingDownload.asStateFlow()

    private val _events = MutableSharedFlow<BrowserEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<BrowserEvent> = _events.asSharedFlow()

    val bookmarks: StateFlow<List<BookmarkEntity>> =
        bookmarkDao.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val history: StateFlow<List<HistoryEntity>> =
        historyDao.observe(500).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val settings: StateFlow<AppSettings> =
        container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val hideFixedBars: StateFlow<Boolean> =
        settings.map { it.hideFixedBars }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    @Volatile
    var userAgent: String? = null

    /** 이번 실행 동안 번역을 거절한 사이트와, 이미 번역을 제안한 주소. */
    private val translationDeclinedHosts = mutableSetOf<String>()
    private val translationOfferedUrls = mutableSetOf<String>()

    init {
        // 탭 목록·주소·제목이 바뀌면 잠시 뒤 저장한다.
        viewModelScope.launch(Dispatchers.IO) {
            _tabs.flatMapLatest { tabs -> combine(tabs.map { it.ui }) { tabs } }
                .combine(_currentTabId) { _, _ -> snapshot() }
                .debounce(1_000)
                .collect { tabStore.save(it) }
        }
    }

    /** 앱이 백그라운드로 갈 때 바로 저장한다(디바운스 대기 중 종료돼도 잃지 않게). */
    fun saveTabsNow() {
        val state = snapshot()
        viewModelScope.launch(Dispatchers.IO) { tabStore.save(state) }
    }

    private fun snapshot(): SavedTabs = SavedTabs(
        tabs = _tabs.value.map { tab ->
            val ui = tab.ui.value
            SavedTab(tab.id, ui.url, ui.title, ui.showHome)
        },
        currentTabId = _currentTabId.value,
    )

    /** 복원된 탭이면 처음 한 번만 불러올 주소를 돌려준다. */
    fun consumeRestoreUrl(tabId: Long): String? {
        val tab = tab(tabId) ?: return null
        return tab.restoreUrl.also { tab.restoreUrl = null }
    }

    // ---- 탭 관리 ----

    /** 새 탭을 만들고 현재 탭으로 바꾼다. 한도를 넘으면 null. */
    fun newTab(showHome: Boolean = true): Long? {
        if (_tabs.value.size >= MAX_TABS) {
            _events.tryEmit(BrowserEvent.Message("탭은 최대 ${MAX_TABS}개까지 열 수 있습니다"))
            return null
        }
        val tab = TabSession(nextTabId.getAndIncrement(), showHome)
        _tabs.update { it + tab }
        _currentTabId.value = tab.id
        return tab.id
    }

    fun switchTab(id: Long) {
        if (_tabs.value.any { it.id == id }) _currentTabId.value = id
    }

    fun closeTab(id: Long) {
        val tabs = _tabs.value
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val remaining = tabs - tabs[index]
        // 현재 탭을 먼저 옮긴 뒤 목록에서 뺀다. 화면이 닫힌 탭의 WebView 를 다시 요청하지 않게 하기 위해서다.
        if (remaining.isEmpty()) {
            replaceAllWithFreshTab()
            return
        }
        if (_currentTabId.value == id) _currentTabId.value = remaining[(index - 1).coerceAtLeast(0)].id
        _tabs.value = remaining
    }

    fun closeAllTabs() = replaceAllWithFreshTab()

    private fun replaceAllWithFreshTab() {
        val fresh = TabSession(nextTabId.getAndIncrement(), showHome = true)
        _tabs.update { it + fresh }
        _currentTabId.value = fresh.id
        _tabs.value = listOf(fresh)
    }

    private fun tab(id: Long): TabSession? = _tabs.value.firstOrNull { it.id == id }

    private fun current(): TabSession? = tab(_currentTabId.value)

    fun resolveInput(input: String): String {
        val text = input.trim()
        if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) return text
        if (!text.contains(' ') && Patterns.WEB_URL.matcher(text).matches()) return "https://$text"
        return settings.value.searchEngine.queryUrl(text)
    }

    fun showHome() {
        current()?.ui?.update { it.copy(showHome = true) }
    }

    fun showPage() {
        current()?.ui?.update { it.copy(showHome = false, hasPage = true) }
    }

    // ---- WebView 콜백 (onResourceRequest 만 백그라운드 스레드에서 호출된다) ----

    fun onPageStarted(tabId: Long, url: String) {
        val tab = tab(tabId) ?: return
        if (stripFragment(url) != tab.pageUrl?.let(::stripFragment)) {
            tab.detectedAll.value = emptyList()
            tab.hiddenUrls.value = emptySet()
            tab.youtubeId = null
        }
        tab.pageUrl = url
        tab.ui.update { it.copy(url = url, loading = true, showHome = false, hasPage = true) }
        checkYouTube(tab, url)
    }

    fun onPageFinished(tabId: Long, url: String, title: String?) {
        val tab = tab(tabId) ?: return
        tab.pageUrl = url
        tab.title = title
        tab.ui.update { it.copy(url = url, title = title.orEmpty(), loading = false, progress = 100) }
        if (settings.value.saveHistory && (url.startsWith("http://") || url.startsWith("https://"))) {
            viewModelScope.launch(Dispatchers.IO) {
                historyDao.upsert(HistoryEntity(url = url, title = title?.takeIf { it.isNotBlank() } ?: url))
            }
        }
    }

    fun onUrlChanged(tabId: Long, url: String) {
        val tab = tab(tabId) ?: return
        tab.pageUrl = url
        tab.ui.update { it.copy(url = url) }
        // 유튜브는 페이지를 새로 읽지 않고 주소만 바꿔 다음 영상으로 넘어간다.
        checkYouTube(tab, url)
    }

    fun onTitle(tabId: Long, title: String?) {
        val tab = tab(tabId) ?: return
        tab.title = title
        tab.ui.update { it.copy(title = title.orEmpty()) }
    }

    fun onProgress(tabId: Long, progress: Int) {
        tab(tabId)?.ui?.update { it.copy(progress = progress, loading = progress < 100) }
    }

    fun onNavState(tabId: Long, canGoBack: Boolean, canGoForward: Boolean) {
        tab(tabId)?.ui?.update { it.copy(canGoBack = canGoBack, canGoForward = canGoForward) }
    }

    fun onResourceRequest(tabId: Long, url: String, headers: Map<String, String>?, isMainFrame: Boolean) {
        if (isMainFrame) return
        val tab = tab(tabId) ?: return
        val kind = MediaSniffer.classifyUrl(url) ?: return
        addMedia(
            tab = tab,
            url = url,
            kind = kind,
            referer = headers?.headerValue("Referer") ?: tab.pageUrl,
            userAgent = headers?.headerValue("User-Agent") ?: userAgent,
            verifyMime = false,
        )
    }

    /** 페이지의 <video> 요소에서 찾은 주소. 확장자가 없으면 응답 형식으로 영상 여부를 확인한다. */
    fun onVideoElements(tabId: Long, urls: List<String>) {
        val tab = tab(tabId) ?: return
        urls.distinct().forEach { url ->
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@forEach
            val kind = MediaSniffer.classifyUrl(url)
            addMedia(tab, url, kind ?: MediaKind.FILE, tab.pageUrl, userAgent, verifyMime = kind == null)
        }
    }

    fun onDownloadRequested(
        tabId: Long,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ) {
        val tab = tab(tabId)
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        _currentTabId.value = tabId
        _pendingDownload.value = DetectedMedia(
            url = url,
            kind = MediaSniffer.classifyMime(mimeType) ?: MediaSniffer.classifyUrl(url) ?: MediaKind.FILE,
            pageUrl = tab?.pageUrl,
            pageTitle = tab?.title,
            referer = tab?.pageUrl,
            userAgent = userAgent ?: this.userAgent,
            mimeType = mimeType,
            sizeBytes = contentLength,
            suggestedName = name,
            probed = true,
        )
    }

    fun dismissPendingDownload() {
        _pendingDownload.value = null
    }

    /** [url] 은 화질 선택 시 고른 하위 플레이리스트 주소(없으면 감지된 주소 그대로). */
    fun startDownload(
        media: DetectedMedia,
        fileName: String,
        isPrivate: Boolean,
        url: String = media.url,
        formatSpec: String? = null,
    ) {
        _pendingDownload.value = null
        viewModelScope.launch {
            runCatching {
                container.downloadManager.enqueue(
                    NewDownload(
                        url = url,
                        kind = media.kind,
                        fileName = fileName,
                        title = media.pageTitle ?: fileName,
                        pageUrl = media.pageUrl,
                        referer = media.referer,
                        userAgent = media.userAgent,
                        mimeType = media.mimeType,
                        formatSpec = formatSpec,
                        isPrivate = isPrivate,
                    ),
                )
            }.onSuccess {
                _events.tryEmit(BrowserEvent.DownloadQueued(fileName))
            }.onFailure {
                _events.tryEmit(BrowserEvent.Message("다운로드를 시작할 수 없습니다: ${it.message}"))
            }
        }
    }

    fun addBookmark(title: String, url: String) {
        viewModelScope.launch {
            bookmarkDao.insert(BookmarkEntity(title = title.ifBlank { url }, url = url))
            _events.tryEmit(BrowserEvent.Message("북마크에 추가했습니다"))
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { bookmarkDao.delete(id) }
    }

    fun deleteHistory(id: Long) {
        viewModelScope.launch { historyDao.delete(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { historyDao.clear() }
    }

    fun currentPageForBookmark(): Pair<String, String>? {
        val tab = current() ?: return null
        val url = tab.pageUrl ?: return null
        return (tab.title ?: url) to url
    }

    private fun addMedia(tab: TabSession, url: String, kind: MediaKind, referer: String?, userAgent: String?, verifyMime: Boolean) {
        var added = false
        tab.detectedAll.update { list ->
            added = false
            if (list.size >= MAX_DETECTED || list.any { it.url == url }) {
                list
            } else {
                added = true
                list + DetectedMedia(
                    url = url,
                    kind = kind,
                    pageUrl = tab.pageUrl,
                    pageTitle = tab.title,
                    referer = referer,
                    userAgent = userAgent,
                )
            }
        }
        if (added) viewModelScope.launch(Dispatchers.IO) { probe(tab, url, verifyMime) }
    }

    private suspend fun probe(tab: TabSession, url: String, verifyMime: Boolean) {
        val media = tab.detectedAll.value.firstOrNull { it.url == url } ?: return
        val probed = runCatching { MediaProbe.probe(container.httpClient, media) }.getOrNull()
        if (probed == null) {
            if (verifyMime) tab.detectedAll.update { list -> list.filterNot { it.url == url } }
            else tab.detectedAll.update { list -> list.map { if (it.url == url) it.copy(probed = true) else it } }
            return
        }
        if (verifyMime && probed.kind == MediaKind.FILE && MediaSniffer.classifyMime(probed.mimeType) == null) {
            tab.detectedAll.update { list -> list.filterNot { it.url == url } }
            return
        }
        tab.detectedAll.update { list -> list.map { if (it.url == url) probed else it } }
        if (probed.variantUrls.isNotEmpty()) tab.hiddenUrls.update { it + probed.variantUrls }
    }

    fun onPageLanguage(tabId: Long, url: String, lang: String, hangulCount: Int, otherCount: Int) {
        if (!settings.value.offerTranslation || tabId != _currentTabId.value) return
        if (PageTranslation.isTranslated(url) || !PageTranslation.looksForeign(lang, hangulCount, otherCount)) return
        val host = url.toHttpUrlOrNull()?.host ?: return
        // 유튜브·구글은 자체 언어 설정이 있어 번역 프록시를 거치면 오히려 깨진다.
        if (host.contains("youtube") || host.contains("google.") || host in translationDeclinedHosts) return
        if (!translationOfferedUrls.add(url.substringBefore('#'))) return
        _events.tryEmit(BrowserEvent.OfferTranslation(tabId, url, PageTranslation.languageName(lang)))
    }

    fun declineTranslation(url: String) {
        url.toHttpUrlOrNull()?.host?.let { translationDeclinedHosts += it }
    }

    /** 유튜브 영상 페이지면 형식 목록을 조회해 감지 목록에 넣는다. 다른 영상으로 넘어가면 이전 항목을 바꾼다. */
    private fun checkYouTube(tab: TabSession, url: String) {
        val id = YouTubeSupport.videoId(url) ?: return
        if (id == tab.youtubeId) return
        tab.youtubeId = id
        val watchUrl = YouTubeSupport.watchUrl(id)
        tab.detectedAll.update { list ->
            list.filterNot { it.kind == MediaKind.YOUTUBE } + DetectedMedia(
                url = watchUrl,
                kind = MediaKind.YOUTUBE,
                pageUrl = url,
                pageTitle = null,
                referer = null,
                userAgent = null,
            )
        }
        viewModelScope.launch {
            val result = runCatching { YouTubeSupport.resolve(watchUrl) }
            if (tab.youtubeId != id) return@launch
            tab.detectedAll.update { list ->
                list.map { media ->
                    if (media.url != watchUrl) return@map media
                    result.fold(
                        onSuccess = { video ->
                            media.copy(
                                pageTitle = video.title,
                                durationSec = video.durationSec,
                                thumbnailUrl = video.thumbnailUrl,
                                ytFormats = video.formats,
                                probed = true,
                            )
                        },
                        onFailure = { media.copy(probed = true, error = it.message ?: "영상 정보를 가져오지 못했습니다") },
                    )
                }
            }
        }
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun stripFragment(url: String) = url.substringBefore('#')

    private companion object {
        const val MAX_DETECTED = 50
        const val MAX_TABS = 10
    }
}
