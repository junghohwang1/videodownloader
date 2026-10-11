package com.example.videodownloader.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/**
 * 광고 차단(모든 사이트).
 * 1) 광고·추적 서버 도메인 목록(StevenBlack hosts, MIT, 약 7만 개)에 든 주소로 가는 요청을 빈 응답으로 막는다.
 *    목록은 처음 실행할 때 받아 앱 저장소에 두고 일주일마다 새로 받는다. 받기 전에는 주요 광고망 기본 목록을 쓴다.
 * 2) 모든 페이지에 흔한 광고 칸을 숨기는 스타일을 넣는다.
 * 3) 유튜브 페이지에는 문서가 시작될 때 스크립트를 넣어, 플레이어 응답에서 광고 정보를 지워 광고가 아예 붙지 않게 하고,
 *    그래도 나온 광고는 음소거 후 끝으로 넘기거나 "건너뛰기"를 누르며, 목록·화면의 광고 칸을 숨긴다.
 * 사이트가 자기 서버에서 직접 내보내는 광고나 유튜브 구조 변경에는 일부 광고가 보일 수 있다.
 */
object AdBlock {

    /** 목록을 받기 전에도 막는 주요 광고망(하위 도메인 포함). */
    private val BUILT_IN_HOSTS = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "googletagservices.com", "amazon-adsystem.com", "adnxs.com", "criteo.com", "criteo.net", "taboola.com",
        "outbrain.com", "adsrvr.org", "pubmatic.com", "rubiconproject.com", "openx.net", "casalemedia.com",
        "smartadserver.com", "moatads.com", "mgid.com", "revcontent.com", "zedo.com", "adform.net", "media.net",
        "33across.com", "sharethrough.com", "teads.tv", "adcash.com", "exoclick.com", "exosrv.com", "magsrv.com",
        "juicyads.com", "trafficjunky.net", "trafficjunky.com", "trafficstars.com", "tsyndicate.com", "popads.net",
        "popcash.net", "propellerads.com", "adsterra.com", "hilltopads.net", "clickadu.com", "a-ads.com",
        "onclickads.net", "realsrv.com", "adspyglass.com",
        // 국내 광고망(네이버·카카오)
        "veta.naver.com", "ad.naver.com", "adcreative.naver.com", "ad.daum.net", "adfit.kakao.com",
    )

    private const val HOSTS_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private val REFRESH_INTERVAL_MS = TimeUnit.DAYS.toMillis(7)

    @Volatile
    private var hosts: Set<String> = BUILT_IN_HOSTS

    private val YOUTUBE_AD_PATHS = listOf(
        "/pagead/",
        "/api/stats/ads",
        "/get_midroll_info",
        "/ptracking",
        "/youtubei/v1/player/ad_break",
    )

    /** 받아 둔 목록을 읽고, 오래됐으면 새로 받는다. 앱 시작 때 한 번 부른다. */
    fun init(context: Context, client: OkHttpClient, scope: CoroutineScope) {
        val cache = File(context.filesDir, "adblock-hosts.txt")
        scope.launch(Dispatchers.IO) {
            runCatching { if (cache.exists()) hosts = parseHosts(cache.readText()) + BUILT_IN_HOSTS }
            if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < REFRESH_INTERVAL_MS) return@launch
            runCatching {
                val text = client.newCall(Request.Builder().url(HOSTS_URL).build()).execute().use { response ->
                    if (!response.isSuccessful) return@launch
                    response.body.string()
                }
                val parsed = parseHosts(text)
                if (parsed.size < 1_000) return@launch // 잘못 받은 응답이면 기존 목록을 유지한다.
                cache.writeText(text)
                hosts = parsed + BUILT_IN_HOSTS
            }
        }
    }

    fun parseHosts(text: String): Set<String> {
        val out = HashSet<String>(100_000)
        text.lineSequence().forEach { line ->
            if (!line.startsWith("0.0.0.0 ")) return@forEach
            val host = line.substring(8).substringBefore('#').trim().lowercase()
            if (host.isNotEmpty() && host != "0.0.0.0" && host.contains('.')) out += host
        }
        return out
    }

    fun isAdRequest(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (isBlockedHost(host)) return true
        if (isYouTubeHost(host)) {
            val path = uri.path ?: return false
            return YOUTUBE_AD_PATHS.any { path.startsWith(it) }
        }
        return false
    }

    /** a.ads.example.com → ads.example.com → example.com 순으로 목록에 있는지 본다. */
    private fun isBlockedHost(host: String): Boolean {
        val set = hosts
        var h = host
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    fun isYouTubeHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "youtube.com" || h.endsWith(".youtube.com")
    }

    const val YOUTUBE_JS = """
(function(){
  if(window.__vdAdBlock) return;
  if(!/(^|\.)youtube\.com$/.test(location.hostname)) return;
  window.__vdAdBlock=true;
  var KEYS=['adPlacements','playerAds','adSlots','adBreakHeartbeatParams'];
  function prune(o){
    try{
      if(o&&typeof o==='object'){
        for(var i=0;i<KEYS.length;i++){ if(KEYS[i] in o) delete o[KEYS[i]]; }
        if(o.playerResponse) prune(o.playerResponse);
      }
    }catch(e){}
    return o;
  }
  var parse=JSON.parse;
  JSON.parse=function(){ return prune(parse.apply(this,arguments)); };
  if(window.Response){
    var json=Response.prototype.json;
    Response.prototype.json=function(){ return json.apply(this,arguments).then(prune); };
  }
  try{
    var ipr=prune(window.ytInitialPlayerResponse);
    Object.defineProperty(window,'ytInitialPlayerResponse',{configurable:true,
      get:function(){return ipr;}, set:function(v){ipr=prune(v);}});
  }catch(e){}

  var CSS='ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,ytm-companion-ad-renderer,'+
    'ytm-companion-slot,ad-slot-renderer,ytm-ad-slot-renderer,ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,'+
    'ytd-promoted-sparkles-web-renderer,ytd-promoted-video-renderer,ytd-banner-promo-renderer,ytd-display-ad-renderer,'+
    'ytd-companion-slot-renderer,ytd-player-legacy-desktop-watch-ads-renderer,#masthead-ad,#player-ads,'+
    '.ytp-ad-overlay-container,.ytp-ad-image-overlay,.ytp-ad-text-overlay,ytm-paid-content-overlay-renderer'+
    '{display:none!important}';
  function addStyle(){
    var root=document.head||document.documentElement;
    if(!root){ setTimeout(addStyle,50); return; }
    var s=document.createElement('style'); s.textContent=CSS; root.appendChild(s);
  }
  addStyle();

  var mutedByUs=false;
  setInterval(function(){
    var player=document.querySelector('.ad-showing,.ad-interrupting');
    var v=(player&&player.querySelector('video'))||document.querySelector('video');
    if(player&&v){
      if(!v.muted){ v.muted=true; mutedByUs=true; }
      if(isFinite(v.duration)&&v.duration>0&&v.currentTime<v.duration-0.1) v.currentTime=v.duration;
      var skip=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button');
      if(skip) skip.click();
    }else if(mutedByUs&&v){
      v.muted=false; mutedByUs=false;
    }
  },300);
})();
"""

    /** 흔한 광고 칸만 숨긴다(넓은 선택자는 일반 내용까지 가릴 수 있어 쓰지 않는다). */
    const val COSMETIC_JS = """
(function(){
  if(window.__vdCosmetic) return; window.__vdCosmetic=true;
  var css='ins.adsbygoogle,.adsbygoogle,[id^="google_ads_"],[id^="div-gpt-ad"],[data-ad-slot],[data-ad-client],'+
    'iframe[id^="google_ads_iframe"],iframe[src*="doubleclick.net"],iframe[src*="googlesyndication.com"],'+
    'iframe[src*="exoclick"],iframe[src*="juicyads"],iframe[src*="trafficjunky"],amp-ad,amp-embed[type="taboola"],'+
    '[id^="taboola-"],.trc_related_container,.OUTBRAIN,[id^="outbrain_widget"],.ad-slot,.ad-banner,.ad-container,'+
    '.adsbox,.advertisement,[aria-label="Advertisement"],[aria-label="광고"]{display:none!important}';
  function addStyle(){
    var root=document.head||document.documentElement;
    if(!root){ setTimeout(addStyle,50); return; }
    var s=document.createElement('style'); s.textContent=css; root.appendChild(s);
  }
  addStyle();
})();
"""

    /** 모든 페이지에 넣는 스크립트. 유튜브 부분은 유튜브 주소에서만 동작한다. */
    private const val PAGE_JS = COSMETIC_JS + YOUTUBE_JS

    private val handlers = WeakHashMap<WebView, ScriptHandler>()

    /** 광고 숨김·유튜브 광고 제거 스크립트를 켜거나 끈다. 문서 시작 스크립트를 지원하지 않는 WebView 는 페이지 로딩 때 넣는다. */
    fun setEnabled(webView: WebView, enabled: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val existing = handlers[webView]
        if (enabled && existing == null) {
            handlers[webView] = WebViewCompat.addDocumentStartJavaScript(webView, PAGE_JS, setOf("*"))
        } else if (!enabled && existing != null) {
            existing.remove()
            handlers.remove(webView)
        }
    }

    /** 문서 시작 스크립트가 없으면 페이지 로딩 중에 직접 넣는다(첫 광고는 못 막을 수 있다). */
    fun injectFallback(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        webView.evaluateJavascript(PAGE_JS, null)
    }
}
