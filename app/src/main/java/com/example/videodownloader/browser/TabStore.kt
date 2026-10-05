package com.example.videodownloader.browser

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedTab(val id: Long, val url: String, val title: String, val showHome: Boolean)

data class SavedTabs(val tabs: List<SavedTab>, val currentTabId: Long)

/**
 * 열린 탭 목록을 앱 내부 파일(JSON)에 저장해 앱을 다시 켜도 복원한다.
 * 탭별 WebView 상태(뒤로·앞으로 기록)는 [stateDir] 에 탭 ID 별 파일로 따로 둔다.
 */
class TabStore(private val file: File, private val stateDir: File) {

    fun saveState(tabId: Long, bytes: ByteArray) {
        stateDir.mkdirs()
        val target = stateFile(tabId)
        val tmp = File(target.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }

    fun loadState(tabId: Long): ByteArray? = stateFile(tabId).takeIf { it.exists() }?.readBytes()

    fun deleteState(tabId: Long) {
        stateFile(tabId).delete()
    }

    /** 열린 탭이 아닌 상태 파일을 지운다. */
    fun retainStates(openTabIds: Collection<Long>) {
        val keep = openTabIds.map { "$it.state" }.toSet()
        stateDir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }

    private fun stateFile(tabId: Long) = File(stateDir, "$tabId.state")

    fun load(): SavedTabs? = runCatching {
        if (!file.exists()) return null
        val root = JSONObject(file.readText())
        val array = root.getJSONArray("tabs")
        val tabs = List(array.length()) { i ->
            val o = array.getJSONObject(i)
            SavedTab(
                id = o.getLong("id"),
                url = o.optString("url"),
                title = o.optString("title"),
                showHome = o.optBoolean("showHome", true),
            )
        }
        if (tabs.isEmpty()) null else SavedTabs(tabs, root.optLong("current", tabs.first().id))
    }.getOrNull()

    @Synchronized
    fun save(state: SavedTabs) {
        val array = JSONArray()
        state.tabs.forEach { tab ->
            array.put(
                JSONObject()
                    .put("id", tab.id)
                    .put("url", tab.url)
                    .put("title", tab.title)
                    .put("showHome", tab.showHome),
            )
        }
        val json = JSONObject().put("current", state.currentTabId).put("tabs", array).toString()
        // 쓰는 도중 앱이 종료돼도 기존 파일이 깨지지 않도록 임시 파일에 쓴 뒤 바꿔치기한다.
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
    }
}
