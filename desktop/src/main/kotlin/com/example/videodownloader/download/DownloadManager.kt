package com.example.videodownloader.download

import com.example.videodownloader.data.DownloadEntity
import com.example.videodownloader.data.DownloadStatus
import com.example.videodownloader.data.DownloadStore
import com.example.videodownloader.data.MediaKind
import com.example.videodownloader.data.SettingsRepository
import com.example.videodownloader.util.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class NewDownload(
    val url: String,
    val kind: MediaKind,
    val fileName: String,
    val title: String,
    val pageUrl: String? = null,
    val referer: String? = null,
    val userAgent: String? = null,
    val mimeType: String? = null,
    val formatSpec: String? = null,
)

/**
 * 다운로드 큐. 상태는 모두 [DownloadStore] 에 저장하고, 실행 중인 작업만 메모리(jobs)에 둔다.
 * 동시 실행 수는 설정값을 따르며, 하나가 끝날 때마다 대기열에서 다음 작업을 꺼낸다.
 */
class DownloadManager(
    private val store: DownloadStore,
    client: OkHttpClient,
    private val settings: SettingsRepository,
    private val storage: Storage,
    ffmpeg: Ffmpeg,
    private val scope: CoroutineScope,
) {
    private val http = HttpDownloader(client)
    private val hls = HlsDownloader(client, storage, ffmpeg)
    private val youtube = YouTubeDownloader(client, storage, ffmpeg)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val scheduleMutex = Mutex()

    private val _speeds = MutableStateFlow<Map<Long, Long>>(emptyMap())
    private val _phases = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val _completed = MutableSharedFlow<DownloadEntity>(extraBufferCapacity = 16)

    /** 다운로드 ID → 초당 바이트. 실행 중인 작업만 들어 있다. */
    val speeds: StateFlow<Map<Long, Long>> = _speeds.asStateFlow()

    /** 다운로드 ID → 받기 외 단계 설명(ffmpeg 설치·합치기 등). */
    val phases: StateFlow<Map<Long, String>> = _phases.asStateFlow()

    /** 완료 알림용. */
    val completed: SharedFlow<DownloadEntity> = _completed.asSharedFlow()

    val downloads: StateFlow<List<DownloadEntity>> = store.items

    val runningCount: Int get() = jobs.size

    fun recoverInterrupted() = store.pauseInterrupted()

    fun enqueue(request: NewDownload): Long {
        val file = storage.reserveFile(storage.downloadDir, request.fileName)
        val id = store.insert(
            DownloadEntity(
                url = request.url,
                pageUrl = request.pageUrl,
                title = request.title,
                fileName = file.name,
                filePath = file.path,
                mimeType = request.mimeType,
                kind = request.kind,
                referer = request.referer,
                userAgent = request.userAgent,
                formatSpec = request.formatSpec,
            ),
        )
        schedule()
        return id
    }

    fun pause(id: Long) {
        store.setStatus(id, DownloadStatus.PAUSED, null)
        jobs[id]?.cancel()
    }

    fun pauseAll() {
        store.active().forEach { store.setStatus(it.id, DownloadStatus.PAUSED, null) }
        jobs.values.forEach { it.cancel() }
    }

    fun resume(id: Long) {
        store.setStatus(id, DownloadStatus.QUEUED, null)
        schedule()
    }

    fun resumeAll() {
        store.items.value
            .filter { it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED }
            .forEach { store.setStatus(it.id, DownloadStatus.QUEUED, null) }
        schedule()
    }

    suspend fun delete(id: Long, deleteFile: Boolean = true) {
        jobs[id]?.cancelAndJoin()
        val entity = store.get(id) ?: return
        store.delete(id)
        if (deleteFile) {
            File(entity.filePath).delete()
            File(entity.filePath + Storage.PART).delete()
            storage.tempDir(id).deleteRecursively()
        } else {
            File(entity.filePath + Storage.PART).delete()
            storage.tempDir(id).deleteRecursively()
        }
    }

    /** 완료 목록에서만 지운다(파일은 남김). */
    fun clearCompleted() {
        store.items.value.filter { it.status == DownloadStatus.COMPLETED }.forEach { store.delete(it.id) }
    }

    fun rename(id: Long, newBaseName: String): String? {
        val entity = store.get(id) ?: return "항목을 찾을 수 없습니다"
        val src = File(entity.filePath)
        if (!src.exists()) return "파일이 없습니다"
        val ext = src.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val dst = storage.uniqueFile(src.parentFile, Storage.sanitizeFileName(newBaseName) + ext)
        if (!src.renameTo(dst)) return "이름을 바꿀 수 없습니다(다른 프로그램에서 사용 중일 수 있습니다)"
        store.updateLocation(id, dst.path, dst.name)
        return null
    }

    private fun schedule() {
        scope.launch {
            scheduleMutex.withLock {
                val max = settings.current().maxConcurrent
                for (task in store.queued()) {
                    if (jobs.size >= max) break
                    if (jobs.containsKey(task.id)) continue
                    start(task)
                }
            }
        }
    }

    private fun start(task: DownloadEntity) {
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { run(task) }
        jobs[task.id] = job
        job.invokeOnCompletion {
            jobs.remove(task.id, job)
            _speeds.update { it - task.id }
            _phases.update { it - task.id }
            schedule()
        }
        job.start()
    }

    private suspend fun run(task: DownloadEntity) {
        store.setStatus(task.id, DownloadStatus.RUNNING, null)
        try {
            val listener = progressListener(task.id)
            val result = when (task.kind) {
                MediaKind.FILE -> http.download(task, listener)
                MediaKind.HLS -> hls.download(task, listener)
                MediaKind.YOUTUBE -> youtube.download(task, listener)
            }
            store.markCompleted(
                id = task.id,
                path = result.file.path,
                name = result.file.name,
                size = result.file.length(),
                mimeType = result.mimeType,
                completedAt = System.currentTimeMillis(),
            )
            store.get(task.id)?.let { _completed.tryEmit(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 일시정지로 Call 이 끊기면 IOException 이 먼저 올라온다. 이 경우 상태를 덮어쓰지 않는다.
            currentCoroutineContext().ensureActive()
            System.err.println("다운로드 실패: ${task.url} — $e")
            store.setStatus(task.id, DownloadStatus.FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun progressListener(id: Long): ProgressListener {
        val lock = Mutex()
        var lastWriteAt = 0L
        var lastBytes = -1L
        return object : ProgressListener {
            override suspend fun onProgress(downloadedBytes: Long, totalBytes: Long, segmentsDone: Int, segmentsTotal: Int) {
                if (now() - lastWriteAt < PROGRESS_INTERVAL_MS) return
                lock.withLock {
                    val now = now()
                    val elapsed = now - lastWriteAt
                    if (elapsed < PROGRESS_INTERVAL_MS) return@withLock
                    if (lastBytes >= 0) {
                        val speed = ((downloadedBytes - lastBytes) * 1000 / elapsed).coerceAtLeast(0)
                        _speeds.update { it + (id to speed) }
                    }
                    lastWriteAt = now
                    lastBytes = downloadedBytes
                    store.setProgress(id, downloadedBytes, totalBytes, segmentsDone, segmentsTotal)
                }
            }

            override suspend fun onPhase(text: String?) {
                _phases.update { if (text == null) it - id else it + (id to text) }
            }
        }
    }

    private fun now() = System.nanoTime() / 1_000_000

    private companion object {
        const val PROGRESS_INTERVAL_MS = 500L
    }
}
