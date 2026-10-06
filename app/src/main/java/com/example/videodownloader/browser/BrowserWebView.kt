package com.example.videodownloader.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray

/** 페이지 안의 <video>/<source> 주소를 모은다. blob: 주소(MSE 스트리밍)는 받을 수 없어 제외한다. */
const val SCAN_VIDEO_JS = """
(function(){
  var out=[];
  var vs=document.querySelectorAll('video');
  for(var i=0;i<vs.length;i++){
    var v=vs[i]; var s=v.currentSrc||v.src;
    if(s && s.indexOf('blob:')!==0) out.push(s);
    var ss=v.querySelectorAll('source');
    for(var j=0;j<ss.length;j++){ if(ss[j].src && ss[j].src.indexOf('blob:')!==0) out.push(ss[j].src); }
  }
  return out;
})()
"""

/**
 * 화면 아래쪽에 고정(position: fixed/sticky)된 메뉴·배너·"맨 위로" 버튼을 숨긴다.
 * 모든 요소를 훑지 않고 화면 하단 몇 지점에 있는 요소만 확인해 가볍게 동작한다.
 * 영상이 들어 있거나 화면의 40% 이상을 차지하는 요소(플레이어, 전체 화면)는 건드리지 않는다.
 */
const val HIDE_FIXED_BARS_JS = """
(function(){
  if(!document.elementsFromPoint) return 0;
  var vw=window.innerWidth, vh=window.innerHeight, hidden=0;
  var xs=[0.03,0.2,0.4,0.5,0.6,0.8,0.97], ys=[vh-3, vh-40, vh-90];
  for(var a=0;a<xs.length;a++){
    for(var b=0;b<ys.length;b++){
      var stack=document.elementsFromPoint(vw*xs[a], ys[b]);
      for(var k=0;k<stack.length;k++){
        var e=stack[k];
        while(e && e!==document.body && e!==document.documentElement){
          var pos=getComputedStyle(e).position;
          if(pos==='fixed'||pos==='sticky'){
            var r=e.getBoundingClientRect();
            if(r.height>0 && r.height<vh*0.4 && r.bottom>=vh*0.75
               && !e.querySelector('video') && !e.dataset.vdHidden){
              e.style.setProperty('display','none','important');
              e.dataset.vdHidden='1';
              hidden++;
            }
            break;
          }
          e=e.parentElement;
        }
      }
    }
  }
  return hidden;
})()
"""

fun WebView.scanVideoElements(onResult: (List<String>) -> Unit) {
    evaluateJavascript(SCAN_VIDEO_JS) { result ->
        val urls = runCatching {
            val array = JSONArray(result ?: "[]")
            List(array.length()) { array.getString(it) }
        }.getOrDefault(emptyList())
        if (urls.isNotEmpty()) onResult(urls)
    }
}

fun WebView.hideFixedBars() {
    if (!keepsFixedBars(url)) evaluateJavascript(HIDE_FIXED_BARS_JS, null)
}

/** 하단 고정 바가 광고가 아니라 사이트의 필수 메뉴인 곳(유튜브 하단 탭 등)은 숨기지 않는다. */
private val KEEP_FIXED_BAR_HOSTS = listOf("youtube.com", "youtube-nocookie.com")

internal fun keepsFixedBars(url: String?): Boolean {
    val host = url?.let { Uri.parse(it).host }?.lowercase() ?: return false
    return KEEP_FIXED_BAR_HOSTS.any { host == it || host.endsWith(".$it") }
}

@SuppressLint("SetJavaScriptEnabled")
fun createBrowserWebView(
    context: Context,
    tabId: Long,
    vm: BrowserViewModel,
    onEnterFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onExitFullscreen: () -> Unit,
    onCreateWindow: (isUserGesture: Boolean, resultMsg: Message) -> Boolean,
): WebView = WebView(context).apply {
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        loadWithOverviewMode = true
        useWideViewPort = true
        builtInZoomControls = true
        displayZoomControls = false
        mediaPlaybackRequiresUserGesture = true
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // target="_blank" 링크는 새 탭으로 연다. 스크립트가 마음대로 창을 띄우는 것(팝업 광고)은 막는다.
        setSupportMultipleWindows(true)
        javaScriptCanOpenWindowsAutomatically = false
    }
    val cookies = CookieManager.getInstance()
    cookies.setAcceptCookie(true)
    cookies.setAcceptThirdPartyCookies(this, true)
    vm.userAgent = settings.userAgentString

    webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // 요청을 가로채지 않고 관찰만 한다.
            vm.onResourceRequest(tabId, request.url.toString(), request.requestHeaders, request.isForMainFrame)
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase()) {
                "http", "https" -> false
                "intent" -> {
                    openIntentScheme(view, uri.toString())
                    true
                }
                else -> {
                    try {
                        view.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: ActivityNotFoundException) {
                    }
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            url?.let { vm.onPageStarted(tabId, it) }
            vm.onNavState(tabId, view.canGoBack(), view.canGoForward())
        }

        override fun onPageFinished(view: WebView, url: String?) {
            url?.let { vm.onPageFinished(tabId, it, view.title) }
            vm.onNavState(tabId, view.canGoBack(), view.canGoForward())
            view.scanVideoElements { vm.onVideoElements(tabId, it) }
            if (vm.hideFixedBars.value) view.hideFixedBars()
            val finishedUrl = url ?: return
            view.evaluateJavascript(PageTranslation.DETECT_LANGUAGE_JS) { result ->
                runCatching {
                    val array = JSONArray(result ?: return@runCatching)
                    vm.onPageLanguage(tabId, finishedUrl, array.optString(0), array.optInt(1), array.optInt(2))
                }
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            url?.let { vm.onUrlChanged(tabId, it) }
            vm.onNavState(tabId, view.canGoBack(), view.canGoForward())
        }
    }

    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) = vm.onProgress(tabId, newProgress)

        override fun onReceivedTitle(view: WebView, title: String?) = vm.onTitle(tabId, title)

        override fun onShowCustomView(view: View, callback: CustomViewCallback) = onEnterFullscreen(view, callback)

        override fun onHideCustomView() = onExitFullscreen()

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean =
            onCreateWindow(isUserGesture, resultMsg)

        override fun onCloseWindow(window: WebView) = vm.closeTab(tabId)
    }

    setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
        vm.onDownloadRequested(tabId, url, userAgent, contentDisposition, mimeType, contentLength)
    }
}

private fun openIntentScheme(view: WebView, url: String) {
    val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return
    try {
        // 웹페이지가 임의의 내부 컴포넌트를 지정하지 못하도록 막는다.
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        view.context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        intent.getStringExtra("browser_fallback_url")?.let(view::loadUrl)
    }
}
