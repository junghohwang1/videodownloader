package com.example.videodownloader.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder
import java.util.Locale

/**
 * 페이지 번역. WebView 에는 크롬의 번역 기능이 없으므로 구글 번역 웹 프록시(*.translate.goog)로 연다.
 */
object PageTranslation {

    /** [lang 속성, 한글 글자 수, 그 밖의 문자 글자 수] 를 돌려준다. */
    const val DETECT_LANGUAGE_JS = """
(function(){
  var l=(document.documentElement.lang||'').trim();
  var t=((document.body&&document.body.innerText)||'').slice(0,3000);
  var h=(t.match(/[가-힣]/g)||[]).length;
  var o=(t.match(/[A-Za-zÀ-ɏЀ-ӿ぀-ヿ一-鿿฀-๿]/g)||[]).length;
  return [l,h,o];
})()
"""

    fun translateUrl(url: String, targetLanguage: String = "ko"): String =
        "https://translate.google.com/translate?sl=auto&tl=$targetLanguage&hl=$targetLanguage&u=" +
            URLEncoder.encode(url, "UTF-8")

    fun isTranslated(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host ?: return false
        return host.endsWith(".translate.goog") || host == "translate.google.com"
    }

    /**
     * 번역 프록시 주소를 원래 주소로 되돌린다.
     * 예) https://www-example-com.translate.goog/a?_x_tr_sl=auto → https://www.example.com/a
     * 호스트 규칙: "-" 는 ".", "--" 는 "-" 를 뜻한다.
     */
    fun originalUrl(url: String): String? {
        val u = url.toHttpUrlOrNull() ?: return null
        if (u.host == "translate.google.com") return u.queryParameter("u")
        if (!u.host.endsWith(".translate.goog")) return null
        val encodedHost = u.host.removeSuffix(".translate.goog")
        val host = encodedHost.replace("--", "\u0000").replace('-', '.').replace('\u0000', '-')
        val builder = u.newBuilder().host(host)
        u.queryParameterNames.filter { it.startsWith("_x_tr_") }.forEach { builder.removeAllQueryParameters(it) }
        return builder.build().toString()
    }

    /** 페이지가 한국어가 아닌 외국어로 보이는지. */
    fun looksForeign(lang: String, hangulCount: Int, otherCount: Int): Boolean {
        val letters = hangulCount + otherCount
        val hangulRatio = if (letters == 0) 0.0 else hangulCount.toDouble() / letters
        if (hangulRatio >= 0.2) return false
        return if (lang.isNotBlank()) !lang.lowercase().startsWith("ko") else letters >= 200 && hangulRatio < 0.05
    }

    /** "en-US" → "영어". 알 수 없으면 null. */
    fun languageName(lang: String): String? =
        lang.takeIf { it.isNotBlank() }
            ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.KOREAN) }
            ?.takeIf { it.isNotBlank() && it != lang }
}
