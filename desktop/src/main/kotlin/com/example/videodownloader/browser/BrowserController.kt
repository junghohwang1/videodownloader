package com.example.videodownloader.browser

import com.example.videodownloader.data.MediaKind
import com.example.videodownloader.download.DownloadOption
import com.example.videodownloader.download.UrlAnalyzer
import com.example.videodownloader.youtube.YouTubeSupport
import dev.datlag.kcef.KCEFBrowser
import dev.datlag.kcef.KCEFClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.cef.browser.CefRendering
import org.cef.callback.CefContextMenuParams
import java.awt.Component

/** 브라우저에서 감지한 미디어 후보. [options] 는 조회가 끝나면 채워진다. */
data class DetectedMedia(
    val url: String,
    val kind: MediaKind,
    val pageUrl: String?,
    val pageTitle: String?,
    val referer: String?,
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val durationSec: Double = 0.0,
    val options: List<DownloadOption> = emptyList(),
    val probed: Boolean = false,
    val error: String? = null,
)

data class BrowserUi(
    val url: String = "",
    val title: String = "",
    /** 메인 페이지를 불러오는 중. 광고 iframe 등 하위 프레임 로딩은 포함하지 않는다. */
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /** 아직 페이지를 열지 않았거나 홈 버튼을 누른 상태(북마크 화면). */
    val showHome: Boolean = true,
    val fullscreen: Boolean = false,
    /** 페이지 영상이 내장 브라우저가 못 푸는 형식(H.264/AAC)이라 재생되지 않는 상태. */
    val unsupportedVideo: Boolean = false,
)

/**
 * 브라우저 탭 하나. 페이지 조작과, 페이지에서 받을 수 있는 영상 찾기를 맡는다(Android 판 BrowserViewModel 과 같은 규칙).
 * - 유튜브 영상 페이지: 주소가 바뀔 때마다 영상 ID 를 보고 화질 목록을 조회
 * - 그 밖의 사이트: 네트워크 요청 중 mp4/webm/m3u8… 주소, 페이지의 <video> 요소를 주기적으로 검사
 * - 우클릭 메뉴: 영상 링크(유튜브 썸네일 등) 위에서 "이 영상 다운로드"
 * CEF 이벤트는 [BrowserTabs] 가 받아 해당 탭의 on… 함수로 넘긴다.
 */
class BrowserController(
    val id: Long,
    private val analyzer: UrlAnalyzer,
    private val bookmarks: BookmarkStore,
    private val scope: CoroutineScope,
) {
    private val _ui = MutableStateFlow(BrowserUi())
    val ui: StateFlow<BrowserUi> = _ui.asStateFlow()

    private val detectedAll = MutableStateFlow<List<DetectedMedia>>(emptyList())

    /** 다운로드 버튼에 보일 감지 목록(마스터 플레이리스트의 하위 화질 주소는 하나로 묶는다). */
    val detected: StateFlow<List<DetectedMedia>> = detectedAll.asStateFlow()

    private val _pending = MutableStateFlow<DetectedMedia?>(null)

    /** 우클릭 메뉴로 고른 영상. 다운로드 창을 바로 연다. */
    val pending: StateFlow<DetectedMedia?> = _pending.asStateFlow()

    private var browser: KCEFBrowser? = null
    private var youtubeId: String? = null
    private var scanJob: Job? = null

    /** 웹페이지를 그리는 AWT 컴포넌트. [create] 후에 채워진다. */
    var component: Component? = null
        private set

    /** CEF 이벤트가 어느 탭 것인지 가리는 데 쓴다. */
    val browserIdentifier: Int? get() = browser?.identifier

    /** 브라우저를 만든다. [url] 이 null 이면 홈(북마크) 화면으로 시작한다. */
    fun create(client: KCEFClient, url: String?) {
        if (browser != null) return
        if (url != null) _ui.update { it.copy(url = url, showHome = false) }
        browser = client.createBrowser(url ?: KCEFBrowser.BLANK_URI, CefRendering.DEFAULT, false)
            .also { component = it.uiComponent }
        startScanning()
    }

    fun resolveInput(input: String): String {
        val text = input.trim()
        if (text.startsWith("http://", true) || text.startsWith("https://", true)) return text
        if (!text.contains(' ') && text.contains('.') && "https://$text".toHttpUrlOrNull() != null) return "https://$text"
        return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(text, Charsets.UTF_8)
    }

    fun load(input: String) {
        if (input.isBlank()) return
        val url = resolveInput(input)
        _ui.update { it.copy(showHome = false, url = url) }
        browser?.loadURL(url)
    }

    fun goBack() {
        val b = browser
        if (!_ui.value.showHome && b != null && b.canGoBack()) b.goBack() else showHome()
    }

    fun goForward() {
        if (_ui.value.showHome && _ui.value.url.isNotBlank() && _ui.value.url != KCEFBrowser.BLANK_URI) {
            _ui.update { it.copy(showHome = false) }
        } else {
            browser?.goForward()
        }
    }

    fun reloadOrStop() {
        val b = browser ?: return
        if (_ui.value.loading) b.stopLoad() else b.reload()
    }

    fun showHome() = _ui.update { it.copy(showHome = true) }

    fun showPage() = _ui.update { it.copy(showHome = false) }

    fun dismissUnsupportedVideo() = _ui.update { it.copy(unsupportedVideo = false) }

    fun dismissPending() {
        _pending.value = null
    }

    fun addCurrentBookmark() {
        val ui = _ui.value
        if (ui.url.isNotBlank() && !ui.showHome) bookmarks.add(ui.title.ifBlank { ui.url }, ui.url)
    }

    /** 탭을 저장할 때 쓸 주소. 홈 화면이면 null. */
    fun savedUrl(): String? = _ui.value.takeIf { !it.showHome && it.url.isNotBlank() }?.url

    fun dispose() {
        scanJob?.cancel()
        browser?.dispose()
        browser = null
        component = null
    }

    // ---- CEF 이벤트(BrowserTabs 가 넘겨 준다) ----

    internal fun onAddressChange(url: String) {
        if (url == KCEFBrowser.BLANK_URI) return
        _ui.update { it.copy(url = url, showHome = false) }
        checkYouTube(url)
    }

    internal fun onTitleChange(title: String?) {
        _ui.update { it.copy(title = title.orEmpty()) }
    }

    internal fun onFullscreenChange(fullscreen: Boolean) {
        _ui.update { it.copy(fullscreen = fullscreen) }
    }

    internal fun onNavigationState(canGoBack: Boolean, canGoForward: Boolean) {
        _ui.update { it.copy(canGoBack = canGoBack, canGoForward = canGoForward) }
    }

    /** 메인 프레임 로딩 시작·끝. 광고 iframe 이 몇 초마다 바뀔 때마다 로딩 표시가 깜빡이지 않도록 메인 프레임만 본다. */
    internal fun onMainFrameLoadStart(url: String?) {
        _ui.update { it.copy(loading = true) }
        url?.let(::onPageStarted)
    }

    internal fun onMainFrameLoadEnd() {
        _ui.update { it.copy(loading = false) }
    }

    internal fun onResourceRequest(url: String) {
        if (_ui.value.showHome) return
        classifyUrl(url)?.let { kind -> addMedia(url, kind) }
    }

    internal fun canDownload(params: CefContextMenuParams?): Boolean = downloadTarget(params) != null

    internal fun downloadFromContextMenu(params: CefContextMenuParams?) {
        downloadTarget(params)?.let(::requestDownload)
    }

    // ---- 감지 ----

    private fun startScanning() {
        scanJob?.cancel()
        // 동적으로 바뀌는 플레이어를 위해 페이지를 보는 동안 주기적으로 <video> 요소를 다시 살핀다.
        scanJob = scope.launch(Dispatchers.IO) {
            var stuckChecks = 0
            while (isActive) {
                delay(3_000)
                val b = browser ?: continue
                if (_ui.value.showHome || isYouTube(_ui.value.url)) continue
                // 두 번 연속(약 6초) 재생 불가 상태면 안내를 띄운다.
                val stuck = runCatching { b.evaluateJavaScript(STUCK_VIDEO_JS) }.getOrNull()?.contains("stuck") == true
                stuckChecks = if (stuck) stuckChecks + 1 else 0
                if (stuckChecks == 2) _ui.update { it.copy(unsupportedVideo = true) }
                val result = runCatching { b.evaluateJavaScript(SCAN_VIDEO_JS) }.getOrNull() ?: continue
                val urls = runCatching { Json.parseToJsonElement(result).jsonArray.map { it.jsonPrimitive.content } }
                    .getOrDefault(emptyList())
                urls.distinct().forEach { url ->
                    if (!url.startsWith("http://") && !url.startsWith("https://")) return@forEach
                    addMedia(url, classifyUrl(url) ?: MediaKind.FILE)
                }
            }
        }
    }

    private fun onPageStarted(url: String) {
        _ui.update { it.copy(unsupportedVideo = false) }
        detectedAll.value = emptyList()
        youtubeId = null
        checkYouTube(url)
    }

    private fun checkYouTube(url: String) {
        val id = YouTubeSupport.videoId(url)
        if (id == null) {
            // 유튜브 안에서 영상 → 목록으로 이동(페이지 새로고침 없음)하면 이전 영상을 지운다.
            if (youtubeId != null) {
                youtubeId = null
                detectedAll.update { list -> list.filterNot { it.kind == MediaKind.YOUTUBE } }
            }
            return
        }
        if (id == youtubeId) return
        youtubeId = id
        val watchUrl = YouTubeSupport.watchUrl(id)
        val media = DetectedMedia(watchUrl, MediaKind.YOUTUBE, url, null, null)
        detectedAll.update { list -> list.filterNot { it.kind == MediaKind.YOUTUBE } + media }
        probe(media) { youtubeId == id }
    }

    private fun addMedia(url: String, kind: MediaKind) {
        val ui = _ui.value
        var added: DetectedMedia? = null
        detectedAll.update { list ->
            added = null
            if (list.size >= MAX_DETECTED || list.any { it.url == url || it.options.any { o -> o.url == url } }) {
                list
            } else {
                DetectedMedia(url, kind, ui.url, ui.title, ui.url).also { added = it }.let { list + it }
            }
        }
        added?.let { media -> probe(media) { true } }
    }

    /** 크기·화질·제목을 조회한다. 유튜브가 아닌데 조회에 실패하면 영상이 아닌 것으로 보고 목록에서 뺀다. */
    private fun probe(media: DetectedMedia, stillWanted: () -> Boolean) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching { analyzer.analyze(media.url, media.referer) }
            if (!stillWanted()) return@launch
            result.fold(
                onSuccess = { r ->
                    val probed = media.copy(
                        title = r.title,
                        thumbnailUrl = r.thumbnailUrl,
                        durationSec = r.durationSec,
                        options = r.options,
                        probed = true,
                    )
                    val hidden = r.options.map { it.url }.toSet() - media.url
                    detectedAll.update { list ->
                        list.filterNot { it.url in hidden }.map { if (it.url == media.url) probed else it }
                    }
                    _pending.update { if (it?.url == media.url) probed else it }
                },
                onFailure = { e ->
                    if (media.kind == MediaKind.YOUTUBE || _pending.value?.url == media.url) {
                        val failed = media.copy(probed = true, error = e.message ?: "영상 정보를 가져오지 못했습니다")
                        detectedAll.update { list -> list.map { if (it.url == media.url) failed else it } }
                        _pending.update { if (it?.url == media.url) failed else it }
                    } else {
                        detectedAll.update { list -> list.filterNot { it.url == media.url } }
                    }
                },
            )
        }
    }

    /** 우클릭한 링크(또는 현재 유튜브 영상)를 다운로드 창으로 연다. */
    private fun requestDownload(url: String) {
        val ui = _ui.value
        val id = YouTubeSupport.videoId(url)
        val media = if (id != null) {
            val watchUrl = YouTubeSupport.watchUrl(id)
            detectedAll.value.firstOrNull { it.url == watchUrl } ?: DetectedMedia(watchUrl, MediaKind.YOUTUBE, ui.url, null, null)
        } else {
            DetectedMedia(url, classifyUrl(url) ?: MediaKind.FILE, ui.url, ui.title, ui.url)
        }
        _pending.value = media
        if (!media.probed) probe(media) { _pending.value?.url == media.url }
    }

    private fun downloadTarget(params: CefContextMenuParams?): String? {
        val link = params?.linkUrl?.takeIf { it.isNotBlank() }
        val media = params?.sourceUrl?.takeIf { it.isNotBlank() && !it.startsWith("blob:") && classifyUrl(it) != null }
        return when {
            link != null && (YouTubeSupport.videoId(link) != null || classifyUrl(link) != null) -> link
            media != null -> media
            // 유튜브 영상 화면의 플레이어 위에서 우클릭
            YouTubeSupport.videoId(_ui.value.url) != null && link == null -> _ui.value.url
            else -> null
        }
    }

    companion object {
        private const val MAX_DETECTED = 50

        private val FILE_EXTENSIONS = setOf("mp4", "m4v", "webm", "mov", "mkv", "3gp", "flv", "avi", "wmv")

        /** 세그먼트 조각(.ts, .m4s)은 제외하고 완결된 파일·플레이리스트만 고른다. */
        fun classifyUrl(url: String): MediaKind? {
            val u = url.toHttpUrlOrNull() ?: return null
            val ext = u.pathSegments.lastOrNull()?.substringAfterLast('.', "")?.lowercase() ?: return null
            return when (ext) {
                "m3u8" -> MediaKind.HLS
                in FILE_EXTENSIONS -> MediaKind.FILE
                else -> null
            }
        }

        fun isYouTube(url: String?): Boolean {
            val host = url?.toHttpUrlOrNull()?.host?.lowercase() ?: return false
            return host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"
        }

        /**
         * 내장 Chromium 은 라이선스 문제로 H.264/AAC 코덱이 없다. 그런 영상(대개 HLS·MP4)은 데이터를 하나도 못 읽고
         * 계속 로딩 상태로 남으므로, 코덱이 없는데 영상이 준비되지 않았거나 형식 오류가 나면 "stuck" 을 돌려준다.
         */
        private const val STUCK_VIDEO_JS = """
return (function(){
  var h264=false;
  try{ h264=!!(window.MediaSource&&MediaSource.isTypeSupported('video/mp4; codecs="avc1.42E01E"')); }catch(e){}
  if(h264) return 'ok';
  var vs=document.querySelectorAll('video');
  for(var i=0;i<vs.length;i++){
    var v=vs[i], s=v.currentSrc||v.src||'';
    if(v.error&&v.error.code>=3) return 'stuck';
    if(v.readyState===0&&s){
      if(s.indexOf('blob:')===0){ if(!v.paused) return 'stuck'; }
      else if(/m3u8|\.mp4|\/hls\//i.test(s)) return 'stuck';
    }
  }
  return 'ok';
})()
"""

        /** 페이지 안의 <video>/<source> 주소를 모은다. blob: 주소(MSE 스트리밍)는 받을 수 없어 제외한다. */
        private const val SCAN_VIDEO_JS = """
return (function(){
  var out=[];
  var vs=document.querySelectorAll('video');
  for(var i=0;i<vs.length;i++){
    var v=vs[i]; var s=v.currentSrc||v.src;
    if(s && s.indexOf('blob:')!==0) out.push(s);
    var ss=v.querySelectorAll('source');
    for(var j=0;j<ss.length;j++){ if(ss[j].src && ss[j].src.indexOf('blob:')!==0) out.push(ss[j].src); }
  }
  return JSON.stringify(out);
})()
"""
    }
}
