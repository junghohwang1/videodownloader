package com.example.videodownloader.download

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.videodownloader.data.db.DownloadDao
import com.example.videodownloader.data.db.DownloadEntity
import com.example.videodownloader.data.db.DownloadStatus
import com.example.videodownloader.data.db.MediaKind
import com.example.videodownloader.data.settings.SettingsRepository
import com.example.videodownloader.util.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.EOFException
import java.io.File
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

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
    val isPrivate: Boolean = false,
)

/**
 * 다운로드 큐. 상태는 모두 Room 에 저장하고, 실행 중인 작업만 메모리(jobs)에 둔다.
 * 동시 실행 수는 설정값을 따르며, 하나가 끝날 때마다 대기열에서 다음 작업을 꺼낸다.
 */
class DownloadManager(
    private val context: Context,
    private val dao: DownloadDao,
    client: OkHttpClient,
    private val settings: SettingsRepository,
    private val storage: Storage,
    private val scope: CoroutineScope,
) {
    private val http = HttpDownloader(client)
    private val hls = HlsDownloader(context, client, storage)
    private val youtube = YouTubeDownloader(client, storage)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val scheduleMutex = Mutex()

    private val _speeds = MutableStateFlow<Map<Long, Long>>(emptyMap())

    /** 다운로드 ID → 초당 바이트. 실행 중인 작업만 들어 있다. */
    val speeds: StateFlow<Map<Long, Long>> = _speeds.asStateFlow()

    val downloads: Flow<List<DownloadEntity>> = dao.observeAll()

    /** 다운로드가 끝날 때마다 불린다(받은 파일, MIME). 자동 자막 만들기에 쓴다. */
    var onCompleted: ((task: DownloadEntity, file: File, mimeType: String?) -> Unit)? = null

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** Wi-Fi 연결 중에 끊긴 다운로드의 자동 재시도 횟수. Wi-Fi 에 새로 연결되면 초기화한다. */
    private val autoRetries = ConcurrentHashMap<Long, Int>()

    init {
        // Wi-Fi 가 (다시) 연결되면 네트워크 문제로 멈춘 다운로드를 이어받는다.
        val wifi = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        connectivity?.registerNetworkCallback(wifi, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                autoRetries.clear()
                resumeWaitingForWifi()
            }
        })
    }

    fun recoverInterrupted() {
        scope.launch { dao.pauseInterrupted() }
    }

    suspend fun enqueue(request: NewDownload): Long {
        val file = storage.reserveFile(storage.dirFor(request.isPrivate), request.fileName)
        val id = dao.insert(
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
                isPrivate = request.isPrivate,
            ),
        )
        kick()
        return id
    }

    fun pause(id: Long) {
        scope.launch {
            dao.setStatus(id, DownloadStatus.PAUSED, null)
            jobs[id]?.cancel()
        }
    }

    fun pauseAll() {
        scope.launch {
            dao.active().forEach { dao.setStatus(it.id, DownloadStatus.PAUSED, null) }
            jobs.values.forEach { it.cancel() }
        }
    }

    fun resume(id: Long) {
        autoRetries.remove(id)
        scope.launch {
            dao.setStatus(id, DownloadStatus.QUEUED, null)
            kick()
        }
    }

    private fun resumeWaitingForWifi() {
        scope.launch {
            if (dao.requeuePaused(WAITING_FOR_WIFI) > 0) kick()
        }
    }

    suspend fun delete(id: Long, deleteFile: Boolean = true) {
        jobs[id]?.cancelAndJoin()
        val entity = dao.get(id) ?: return
        dao.delete(id)
        if (deleteFile) {
            File(entity.filePath).delete()
            File(entity.filePath + Storage.PART).delete()
            Storage.subtitleFor(File(entity.filePath)).delete()
            storage.tempDir(id).deleteRecursively()
        }
    }

    private fun kick() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        } catch (e: IllegalStateException) {
            // 백그라운드에서는 포그라운드 서비스를 시작할 수 없다. 다운로드 자체는 프로세스 범위에서 계속된다.
            Log.w(TAG, "다운로드 서비스를 시작할 수 없습니다", e)
        }
        schedule()
    }

    private fun schedule() {
        scope.launch {
            scheduleMutex.withLock {
                val max = settings.current().maxConcurrent
                for (task in dao.queued()) {
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
            schedule()
        }
        job.start()
    }

    private suspend fun run(task: DownloadEntity) {
        val current = settings.current()
        if (current.wifiOnly && isMeteredNetwork()) {
            dao.setStatus(task.id, DownloadStatus.PAUSED, WAIT_WIFI_ONLY)
            return
        }
        dao.setStatus(task.id, DownloadStatus.RUNNING, null)
        try {
            val listener = progressListener(task.id)
            val result = when (task.kind) {
                MediaKind.FILE -> http.download(task, listener)
                MediaKind.HLS -> hls.download(task, listener)
                MediaKind.YOUTUBE -> youtube.download(task, listener)
            }
            dao.markCompleted(
                id = task.id,
                path = result.file.path,
                name = result.file.name,
                size = result.file.length(),
                mimeType = result.mimeType,
                completedAt = System.currentTimeMillis(),
            )
            autoRetries.remove(task.id)
            onCompleted?.invoke(task, result.file, result.mimeType)
            if (current.notifyOnComplete) {
                Notifications.showCompleted(context, task.id, result.file, result.mimeType, task.isPrivate)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 일시정지로 Call 이 끊기면 IOException 이 먼저 올라온다. 이 경우 상태를 덮어쓰지 않는다.
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "다운로드 실패: ${task.url}", e)
            if (isNetworkError(e) && waitForWifi(task.id)) return
            dao.setStatus(task.id, DownloadStatus.FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 네트워크 문제로 끊긴 다운로드를 "Wi-Fi 대기"로 일시정지한다. 받은 부분은 남아 있어 재개 시 이어받는다.
     * Wi-Fi 가 아직 연결돼 있으면(순간 끊김·서버 쪽 끊김) 잠시 뒤 몇 번 다시 시도하고,
     * 그래도 안 되면 false 를 돌려 실패로 처리하게 한다.
     */
    private suspend fun waitForWifi(id: Long): Boolean {
        if (isWifiConnected()) {
            val attempt = autoRetries.merge(id, 1, Int::plus) ?: 1
            if (attempt > MAX_AUTO_RETRIES) {
                autoRetries.remove(id)
                return false
            }
            dao.setStatus(id, DownloadStatus.PAUSED, WAIT_DISCONNECTED)
            scope.launch {
                delay(RETRY_DELAY_MS * attempt)
                // 그 사이 Wi-Fi 가 끊겼으면 모바일 데이터로 받지 않고 Wi-Fi 연결 콜백을 기다린다.
                if (isWifiConnected()) resumeWaitingForWifi()
            }
        } else {
            dao.setStatus(id, DownloadStatus.PAUSED, WAIT_DISCONNECTED)
        }
        return true
    }

    private fun isNetworkError(e: Throwable): Boolean {
        if (!hasInternet()) return true
        return generateSequence(e) { it.cause }.any {
            it is SocketException || it is InterruptedIOException || it is UnknownHostException ||
                it is SSLException || it is EOFException
        }
    }

    private fun activeCapabilities(): NetworkCapabilities? =
        connectivity?.let { it.getNetworkCapabilities(it.activeNetwork) }

    private fun hasInternet(): Boolean =
        activeCapabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun isWifiConnected(): Boolean =
        activeCapabilities()?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } == true

    private fun progressListener(id: Long): ProgressListener {
        val lock = Mutex()
        var lastWriteAt = 0L
        var lastBytes = -1L
        return ProgressListener { downloaded, total, segmentsDone, segmentsTotal ->
            if (SystemClock.elapsedRealtime() - lastWriteAt < PROGRESS_INTERVAL_MS) return@ProgressListener
            lock.withLock {
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - lastWriteAt
                if (elapsed < PROGRESS_INTERVAL_MS) return@withLock
                if (lastBytes >= 0) {
                    val speed = ((downloaded - lastBytes) * 1000 / elapsed).coerceAtLeast(0)
                    _speeds.update { it + (id to speed) }
                }
                lastWriteAt = now
                lastBytes = downloaded
                dao.setProgress(id, downloaded, total, segmentsDone, segmentsTotal)
            }
        }
    }

    private fun isMeteredNetwork(): Boolean = connectivity?.isActiveNetworkMetered ?: false

    private companion object {
        const val TAG = "DownloadManager"
        const val PROGRESS_INTERVAL_MS = 500L
        const val MAX_AUTO_RETRIES = 3
        const val RETRY_DELAY_MS = 5_000L

        // 일시정지 사유. 아래 사유로 멈춘 항목만 Wi-Fi 연결 시 자동으로 재개한다(직접 일시정지한 항목은 제외).
        const val WAIT_DISCONNECTED = "네트워크 연결이 끊겼습니다. Wi-Fi 에 연결되면 자동으로 이어받습니다."
        const val WAIT_WIFI_ONLY = "Wi-Fi 전용 모드입니다. Wi-Fi 에 연결되면 자동으로 이어받습니다."
        val WAITING_FOR_WIFI = listOf(WAIT_DISCONNECTED, WAIT_WIFI_ONLY)
    }
}
