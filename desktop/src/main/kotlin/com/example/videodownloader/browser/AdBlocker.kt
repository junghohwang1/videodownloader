package com.example.videodownloader.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 광고 차단(모든 사이트).
 * 1) 광고·추적 서버 도메인 목록(StevenBlack hosts, MIT, 약 7만 개)에 든 주소로 가는 요청을 막는다.
 *    목록은 처음 실행할 때 받아 [cacheFile] 에 두고 일주일마다 새로 받는다. 받기 전에는 주요 광고망 기본 목록을 쓴다.
 * 2) 페이지에 남는 광고 칸은 흔한 광고 요소 선택자로 숨긴다([pageScript]).
 * 3) 유튜브는 영상 광고를 건너뛰고 광고 칸을 숨긴다.
 */
class AdBlocker(
    private val client: OkHttpClient,
    private val cacheFile: File,
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
) {
    @Volatile
    private var hosts: Set<String> = BUILT_IN_HOSTS

    fun start() {
        scope.launch(Dispatchers.IO) {
            runCatching { if (cacheFile.exists()) hosts = parseHosts(cacheFile.readText()) + BUILT_IN_HOSTS }
            val stale = !cacheFile.exists() || System.currentTimeMillis() - cacheFile.lastModified() > TimeUnit.DAYS.toMillis(7)
            if (stale) runCatching { refresh() }
        }
    }

    private fun refresh() {
        val request = Request.Builder().url(HOSTS_URL).build()
        val text = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            response.body.string()
        }
        val parsed = parseHosts(text)
        if (parsed.size < 1_000) return // 잘못 받은 응답이면 기존 목록을 유지한다.
        cacheFile.parentFile?.mkdirs()
        cacheFile.writeText(text)
        hosts = parsed + BUILT_IN_HOSTS
    }

    fun isAdRequest(url: String): Boolean {
        if (!enabled()) return false
        val u = url.toHttpUrlOrNull() ?: return false
        if (isBlockedHost(u.host.lowercase())) return true
        return isYouTubeHost(u.host) && YOUTUBE_AD_PATHS.any { u.encodedPath.startsWith(it) }
    }

    /** a.b.ads.example.com → a.b.ads.example.com, b.ads.example.com, ads.example.com, example.com 순으로 확인한다. */
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

    /**
     * 모든 페이지에서 문서가 만들어질 때(페이지 스크립트보다 먼저) 실행할 스크립트. 새 탭을 만들 때 등록한다.
     * 유튜브 플레이어 정보(ytInitialPlayerResponse, /youtubei/v1/player 응답)에서 광고 항목을 지워 영상 광고가 아예 붙지 않게 한다.
     */
    fun earlyScripts(): List<String> = if (enabled()) listOf(YOUTUBE_PRUNE_JS) else emptyList()

    /** 페이지 로딩이 끝나면 넣을 스크립트. 광고 차단이 꺼져 있으면 null. */
    fun pageScript(url: String): String? {
        if (!enabled()) return null
        val host = url.toHttpUrlOrNull()?.host ?: return null
        return if (isYouTubeHost(host)) COSMETIC_JS + YOUTUBE_JS else COSMETIC_JS
    }

    companion object {
        private const val HOSTS_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

        fun parseHosts(text: String): Set<String> {
            val out = HashSet<String>(100_000)
            text.lineSequence().forEach { line ->
                if (!line.startsWith("0.0.0.0 ")) return@forEach
                val host = line.substring(8).substringBefore('#').trim().lowercase()
                if (host.isNotEmpty() && host != "0.0.0.0" && host.contains('.')) out += host
            }
            return out
        }

        fun isYouTubeHost(host: String): Boolean {
            val h = host.lowercase()
            return h == "youtube.com" || h.endsWith(".youtube.com")
        }

        /** 목록을 받기 전에도 막는 주요 광고망(하위 도메인 포함). */
        val BUILT_IN_HOSTS = setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "googletagservices.com", "pagead2.googlesyndication.com", "amazon-adsystem.com", "adnxs.com",
            "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "adsrvr.org", "pubmatic.com",
            "rubiconproject.com", "openx.net", "casalemedia.com", "smartadserver.com", "moatads.com",
            "mgid.com", "revcontent.com", "zedo.com", "adform.net", "media.net", "33across.com",
            "sharethrough.com", "teads.tv", "adcash.com", "exoclick.com", "exosrv.com", "magsrv.com",
            "juicyads.com", "trafficjunky.net", "trafficjunky.com", "trafficstars.com", "tsyndicate.com",
            "popads.net", "popcash.net", "propellerads.com", "adsterra.com", "hilltopads.net",
            "clickadu.com", "a-ads.com", "onclickads.net", "realsrv.com", "adspyglass.com",
            // 국내 광고망(네이버·카카오)
            "veta.naver.com", "ad.naver.com", "adcreative.naver.com", "ad.daum.net", "adfit.kakao.com",
        )

        private val YOUTUBE_AD_PATHS = listOf(
            "/pagead/", "/api/stats/ads", "/get_midroll_info", "/ptracking", "/youtubei/v1/player/ad_break",
        )

        /** 흔한 광고 칸만 숨긴다(넓은 선택자는 일반 내용까지 가릴 수 있어 쓰지 않는다). */
        private const val COSMETIC_JS = """
(function(){
  if(window.__vdCosmetic) return; window.__vdCosmetic=true;
  var css='ins.adsbygoogle,.adsbygoogle,[id^="google_ads_"],[id^="div-gpt-ad"],[data-ad-slot],[data-ad-client],'+
    'iframe[id^="google_ads_iframe"],iframe[src*="doubleclick.net"],iframe[src*="googlesyndication.com"],'+
    'iframe[src*="exoclick"],iframe[src*="juicyads"],iframe[src*="trafficjunky"],amp-ad,amp-embed[type="taboola"],'+
    '[id^="taboola-"],.trc_related_container,.OUTBRAIN,[id^="outbrain_widget"],.ad-slot,.ad-banner,.ad-container,'+
    '.adsbox,.advertisement,[aria-label="Advertisement"],[aria-label="광고"]{display:none!important}';
  var s=document.createElement('style'); s.textContent=css;
  (document.head||document.documentElement).appendChild(s);
})();
"""

        /** 유튜브 플레이어 응답에서 광고 정보를 지운다(Android 판 AdBlock 과 같은 방식). */
        private const val YOUTUBE_PRUNE_JS = """
(function(){
  if(window.__vdYtPrune) return; window.__vdYtPrune=true;
  if(!/(^|\.)youtube\.com$/.test(location.hostname)) return;
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
})();
"""

        /** 유튜브 영상 광고는 음소거 후 끝으로 넘기거나 "건너뛰기"를 누르고, 광고 칸을 숨긴다. */
        private const val YOUTUBE_JS = """
(function(){
  if(window.__vdYtAds) return; window.__vdYtAds=true;
  var css='ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytd-promoted-sparkles-web-renderer,ytd-promoted-video-renderer,'+
    'ytd-banner-promo-renderer,ytd-display-ad-renderer,ytd-companion-slot-renderer,ytd-player-legacy-desktop-watch-ads-renderer,'+
    '#masthead-ad,#player-ads,.ytp-ad-overlay-container,.ytp-ad-image-overlay,.ytp-ad-text-overlay{display:none!important}';
  var s=document.createElement('style'); s.textContent=css;
  (document.head||document.documentElement).appendChild(s);
  var mutedByUs=false;
  setInterval(function(){
    var player=document.querySelector('.ad-showing,.ad-interrupting');
    var v=(player&&player.querySelector('video'))||document.querySelector('video');
    if(player&&v){
      if(!v.muted){ v.muted=true; mutedByUs=true; }
      if(isFinite(v.duration)&&v.duration>0&&v.currentTime<v.duration-0.1) v.currentTime=v.duration;
      var skip=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button');
      if(skip) skip.click();
    }else if(mutedByUs&&v){ v.muted=false; mutedByUs=false; }
  },300);
})();
"""
    }
}
