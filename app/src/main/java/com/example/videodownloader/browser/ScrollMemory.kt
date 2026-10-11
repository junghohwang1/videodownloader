package com.example.videodownloader.browser

import android.webkit.WebView
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * 페이지(URL)별 스크롤 위치를 기억했다가 뒤로·앞으로 이동한 뒤 그 위치로 되돌린다.
 * 캐시 없이 다시 불러오는 페이지나, 목록을 늦게 그리는 사이트에서도 원래 위치로 돌아가도록
 * 페이지 높이가 충분해질 때까지 몇 번 나눠서 스크롤한다.
 */
internal class ScrollMemory {
    private val positions = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>) = size > MAX_ENTRIES
    }

    /** 페이지를 불러오는 중에는 스크롤이 0 으로 초기화되므로 기록하지 않는다. */
    var loading = false
    private var pending: Pair<String, Int>? = null
    private var restoreToken = 0

    fun record(url: String?, scrollY: Int) {
        if (!loading && url != null) positions[url] = scrollY
    }

    fun prepare(targetUrl: String?) {
        pending = targetUrl?.let { url -> positions[url]?.takeIf { it > 0 }?.let { url to it } }
    }

    fun restore(view: WebView, url: String?) {
        val (targetUrl, y) = pending ?: return
        if (url != targetUrl) return
        val token = ++restoreToken
        RETRY_DELAYS_MS.forEachIndexed { i, delayMs ->
            view.postDelayed({
                if (token != restoreToken || pending?.first != view.url) return@postDelayed
                view.scrollTo(view.scrollX, y)
                // 끝까지 내려갔거나 마지막 시도면 멈춘다.
                if (abs(view.scrollY - y) <= TOLERANCE_PX || i == RETRY_DELAYS_MS.lastIndex) pending = null
            }, delayMs)
        }
    }

    private companion object {
        const val MAX_ENTRIES = 200
        const val TOLERANCE_PX = 8
        val RETRY_DELAYS_MS = longArrayOf(0, 250, 600, 1_200, 2_000, 3_000)
    }
}

private val memories = WeakHashMap<WebView, ScrollMemory>()

internal val WebView.scrollMemory: ScrollMemory
    get() = memories.getOrPut(this) { ScrollMemory() }

fun WebView.goBackRestoringScroll() = stepRestoringScroll(-1)

fun WebView.goForwardRestoringScroll() = stepRestoringScroll(1)

private fun WebView.stepRestoringScroll(steps: Int) {
    val list = copyBackForwardList()
    scrollMemory.record(url, scrollY)
    scrollMemory.prepare(list.getItemAtIndex(list.currentIndex + steps)?.url)
    goBackOrForward(steps)
}
