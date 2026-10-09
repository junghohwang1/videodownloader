package com.example.videodownloader.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 다운로드 목록 저장소. Android 판의 Room 대신 JSON 파일 하나에 저장한다(항목 수가 많지 않다).
 * 메모리의 상태가 기준이고, 바뀌면 잠시 모아 두었다가 파일에 원자적으로 쓴다.
 */
class DownloadStore(private val file: File, scope: CoroutineScope) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val _items = MutableStateFlow(load())

    /** 최신 항목이 앞에 온다. */
    val items: StateFlow<List<DownloadEntity>> = _items.asStateFlow()

    init {
        @OptIn(FlowPreview::class)
        _items.drop(1).debounce(300).onEach { save(it) }.launchIn(CoroutineScope(scope.coroutineContext + Dispatchers.IO))
    }

    fun get(id: Long): DownloadEntity? = _items.value.firstOrNull { it.id == id }

    fun queued(): List<DownloadEntity> =
        _items.value.filter { it.status == DownloadStatus.QUEUED }.sortedBy { it.createdAt }

    fun active(): List<DownloadEntity> = _items.value.filter { it.status.isActive }

    @Synchronized
    fun insert(entity: DownloadEntity): Long {
        val id = (_items.value.maxOfOrNull { it.id } ?: 0L) + 1
        _items.update { listOf(entity.copy(id = id)) + it }
        return id
    }

    fun delete(id: Long) = _items.update { list -> list.filterNot { it.id == id } }

    fun setStatus(id: Long, status: DownloadStatus, error: String?) =
        modify(id) { it.copy(status = status, error = error) }

    fun setProgress(id: Long, downloaded: Long, total: Long, segmentsDone: Int, segmentsTotal: Int) =
        modify(id) {
            it.copy(downloadedBytes = downloaded, totalBytes = total, segmentsDone = segmentsDone, segmentsTotal = segmentsTotal)
        }

    fun markCompleted(id: Long, path: String, name: String, size: Long, mimeType: String?, completedAt: Long) =
        modify(id) {
            it.copy(
                status = DownloadStatus.COMPLETED, error = null, completedAt = completedAt,
                filePath = path, fileName = name, totalBytes = size, downloadedBytes = size, mimeType = mimeType,
            )
        }

    fun updateLocation(id: Long, path: String, name: String) = modify(id) { it.copy(filePath = path, fileName = name) }

    /** 앱이 종료되며 끊긴 작업은 다음 실행 때 일시정지 상태로 돌린다. */
    fun pauseInterrupted() = _items.update { list ->
        list.map { if (it.status.isActive) it.copy(status = DownloadStatus.PAUSED) else it }
    }

    /** 종료 직전 대기 중인 저장을 즉시 반영한다. */
    fun flush() = save(_items.value)

    private inline fun modify(id: Long, crossinline change: (DownloadEntity) -> DownloadEntity) =
        _items.update { list -> list.map { if (it.id == id) change(it) else it } }

    private fun load(): List<DownloadEntity> = runCatching {
        if (file.exists()) json.decodeFromString<List<DownloadEntity>>(file.readText()) else emptyList()
    }.getOrElse { emptyList() }

    @Synchronized
    private fun save(list: List<DownloadEntity>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(list))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
