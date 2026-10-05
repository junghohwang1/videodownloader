package com.example.videodownloader.browser

import android.content.Context
import android.os.Bundle
import android.os.Message
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 탭 ID → WebView. 탭마다 WebView 를 하나씩 두어 페이지·뒤로가기 기록·로그인 상태를 따로 유지한다.
 * Activity 컴포지션에서 하나만 만들고, 닫힌 탭의 WebView 는 [sync] 에서 정리한다.
 */
class BrowserTabs(
    private val context: Context,
    private val vm: BrowserViewModel,
    private val store: TabStore,
    private val scope: CoroutineScope,
    private val onEnterFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    private val onExitFullscreen: () -> Unit,
) {
    private val views = mutableMapOf<Long, WebView>()

    fun get(tabId: Long): WebView = views.getOrPut(tabId) {
        createBrowserWebView(context, tabId, vm, onEnterFullscreen, onExitFullscreen, ::openInNewTab).also { view ->
            // 앱을 다시 켠 뒤 복원된 탭이면 뒤로·앞으로 기록까지 되살리고, 실패하면 마지막 주소만 연다.
            val url = vm.consumeRestoreUrl(tabId) ?: return@also
            if (!restoreHistory(view, tabId)) view.loadUrl(url)
        }
    }

    /** 살아 있는 모든 탭의 WebView 상태(기록)를 저장한다. 앱이 백그라운드로 갈 때 부른다. */
    fun saveStates() {
        views.forEach { (tabId, view) ->
            if (view.url.isNullOrEmpty()) return@forEach
            val bundle = Bundle()
            if (view.saveState(bundle) == null) return@forEach
            val bytes = marshall(bundle)
            scope.launch(Dispatchers.IO) { runCatching { store.saveState(tabId, bytes) } }
        }
    }

    private fun restoreHistory(view: WebView, tabId: Long): Boolean {
        val bytes = runCatching { store.loadState(tabId) }.getOrNull() ?: return false
        return runCatching {
            val bundle = unmarshall(bytes)
            val list = view.restoreState(bundle)
            list != null && list.size > 0
        }.getOrDefault(false)
    }

    private fun marshall(bundle: Bundle): ByteArray {
        val parcel = Parcel.obtain()
        return try {
            bundle.writeToParcel(parcel, 0)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    private fun unmarshall(bytes: ByteArray): Bundle {
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    /** 열린 탭 목록에 없는 WebView 를 파기한다. */
    fun sync(openTabIds: List<Long>) {
        val open = openTabIds.toSet()
        views.keys.filter { it !in open }.forEach { id -> views.remove(id)?.release() }
        scope.launch(Dispatchers.IO) { runCatching { store.retainStates(open) } }
    }

    /** 보이는 탭만 동작시키고 나머지는 일시정지(영상 소리·애니메이션 정지)한다. */
    fun setActive(tabId: Long) {
        views.forEach { (id, view) -> if (id == tabId) view.onResume() else view.onPause() }
    }

    fun destroyAll() {
        views.values.forEach { it.release() }
        views.clear()
    }

    private fun openInNewTab(isUserGesture: Boolean, resultMsg: Message): Boolean {
        if (!isUserGesture) return false
        val tabId = vm.newTab(showHome = false) ?: return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        transport.webView = get(tabId)
        resultMsg.sendToTarget()
        return true
    }

    private fun WebView.release() {
        (parent as? ViewGroup)?.removeView(this)
        stopLoading()
        destroy()
    }
}
