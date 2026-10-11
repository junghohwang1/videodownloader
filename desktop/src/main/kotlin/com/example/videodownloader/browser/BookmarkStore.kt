package com.example.videodownloader.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Bookmark(val id: Long, val title: String, val url: String)

/** 홈 화면 북마크 타일. 처음에는 Android 판과 같은 기본 사이트를 넣어 둔다. */
class BookmarkStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Bookmark>> = _items.asStateFlow()

    @Synchronized
    fun add(title: String, url: String) {
        if (_items.value.any { it.url == url }) return
        val id = (_items.value.maxOfOrNull { it.id } ?: 0L) + 1
        _items.update { it + Bookmark(id, title.take(40), url) }
        save()
    }

    @Synchronized
    fun delete(id: Long) {
        _items.update { list -> list.filterNot { it.id == id } }
        save()
    }

    private fun load(): List<Bookmark> =
        runCatching { json.decodeFromString<List<Bookmark>>(file.readText()) }.getOrNull()
            ?: DEFAULTS.mapIndexed { i, (title, url) -> Bookmark(i + 1L, title, url) }

    private fun save() {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(_items.value))
    }

    private companion object {
        val DEFAULTS = listOf(
            "YouTube" to "https://www.youtube.com",
            "Google" to "https://www.google.com",
            "네이버" to "https://www.naver.com",
            "Vimeo" to "https://vimeo.com",
            "Dailymotion" to "https://www.dailymotion.com",
        )
    }
}
