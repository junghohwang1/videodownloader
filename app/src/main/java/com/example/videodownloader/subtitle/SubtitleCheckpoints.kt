package com.example.videodownloader.subtitle

import com.example.videodownloader.youtube.Cue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 자막 만들기를 중간에 멈춰도 이어서 할 수 있게 저장해 둔다.
 * - 진행분: 조각(2분)을 하나 인식할 때마다 그때까지의 자막·언어·다음 위치를 다운로드 ID 별 파일로 저장한다.
 *   영상 파일이 바뀌었으면(크기·수정 시각이 다르면) 쓰지 않는다.
 * - 대기열: 대기·진행 중인 작업 목록. 앱이 강제로 꺼지면 다음 실행 때 이 목록으로 다시 시작한다.
 */
internal class SubtitleCheckpoints(private val dir: File) {

    data class Progress(val language: String?, val nextStart: Double, val cues: List<Cue>)

    data class Pending(val id: Long, val path: String, val title: String, val auto: Boolean)

    private val queueFile = File(dir, "queue.json")

    @Synchronized
    fun load(id: Long, video: File): Progress? = runCatching {
        val json = JSONObject(fileFor(id).readText())
        if (json.getString("video") != video.path || json.getLong("size") != video.length() ||
            json.getLong("modified") != video.lastModified()
        ) {
            return@runCatching null
        }
        val array = json.getJSONArray("cues")
        val cues = (0 until array.length()).map { i ->
            val c = array.getJSONArray(i)
            Cue(c.getDouble(0), c.getDouble(1), c.getString(2))
        }
        Progress(json.optString("language").ifBlank { null }, json.getDouble("next"), cues)
    }.getOrNull()

    @Synchronized
    fun save(id: Long, video: File, progress: Progress) {
        val cues = JSONArray()
        progress.cues.forEach { cues.put(JSONArray().put(it.start).put(it.end).put(it.text)) }
        val json = JSONObject()
            .put("video", video.path)
            .put("size", video.length())
            .put("modified", video.lastModified())
            .put("language", progress.language ?: "")
            .put("next", progress.nextStart)
            .put("cues", cues)
        writeAtomically(fileFor(id), json.toString())
    }

    @Synchronized
    fun delete(id: Long) {
        fileFor(id).delete()
    }

    @Synchronized
    fun pending(): List<Pending> = runCatching {
        val array = JSONArray(queueFile.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Pending(o.getLong("id"), o.getString("path"), o.getString("title"), o.optBoolean("auto"))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun addPending(item: Pending) {
        val list = pending().filterNot { it.id == item.id } + item
        writeQueue(list)
    }

    @Synchronized
    fun removePending(id: Long) {
        writeQueue(pending().filterNot { it.id == id })
    }

    private fun writeQueue(list: List<Pending>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("id", it.id).put("path", it.path).put("title", it.title).put("auto", it.auto))
        }
        writeAtomically(queueFile, array.toString())
    }

    private fun fileFor(id: Long) = File(dir, "progress-$id.json")

    /** 쓰다가 앱이 꺼져도 파일이 깨지지 않도록 임시 파일에 쓴 뒤 바꿔치기한다. */
    private fun writeAtomically(target: File, text: String) {
        dir.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
