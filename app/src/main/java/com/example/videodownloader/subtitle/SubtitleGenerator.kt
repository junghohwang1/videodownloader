package com.example.videodownloader.subtitle

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.videodownloader.util.Storage
import com.example.videodownloader.youtube.Cue
import com.example.videodownloader.youtube.FetchedSubtitles
import com.example.videodownloader.youtube.SubtitleTranslator
import com.example.videodownloader.youtube.YouTubeSubtitles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/**
 * 「자막 만들기」: 영상의 말소리를 기기 안에서 인식(whisper.cpp)하고, 한국어가 아니면 한국어로 번역해
 * 영상 옆에 .srt 로 저장한다. 무거운 작업이라 한 번에 하나씩 차례로 처리한다.
 */
class SubtitleGenerator(
    private val context: Context,
    client: OkHttpClient,
    private val scope: CoroutineScope,
    /** 설정의 「빠른 자막 만들기(1.5배속)」 */
    private val fastMode: suspend () -> Boolean = { false },
    /** 설정의 「동시에 2개씩 인식(실험)」 */
    private val parallelMode: suspend () -> Boolean = { false },
) {
    enum class Phase(val label: String) {
        WAITING("자막 대기 중"),
        MODEL("음성 인식 모델 받는 중"),
        RECOGNIZING("음성 인식 중"),
        TRANSLATING("한국어로 번역 중"),
    }

    /** [detail] 은 남은 시간 등 덧붙일 설명. */
    data class Progress(val phase: Phase, val fraction: Float?, val detail: String? = null)

    private data class Request(val id: Long, val video: File, val title: String, val auto: Boolean)

    val model = SpeechModel(context, client)

    private val _jobs = MutableStateFlow<Map<Long, Progress>>(emptyMap())

    /** 다운로드 ID → 진행 상태. 대기·진행 중인 것만 들어 있다. */
    val jobs: StateFlow<Map<Long, Progress>> = _jobs.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val queue = Channel<Request>(Channel.UNLIMITED)
    private var currentId: Long? = null
    private var currentJob: Job? = null

    /** 중간 저장(이어서 하기)과, 앱이 꺼져도 남는 대기열. */
    private val checkpoints = SubtitleCheckpoints(File(context.noBackupFilesDir, "subtitle-work"))

    /** 사용자가 「취소」를 누른 작업. 이 작업은 저장해 둔 진행분도 지운다. */
    private val userCancelled: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())

    init {
        scope.launch {
            for (request in queue) {
                if (request.id !in _jobs.value) continue // 대기 중에 취소됨
                currentId = request.id
                val job = launch(Dispatchers.Default) { run(request) }
                currentJob = job
                job.join()
                currentId = null
                currentJob = null
                checkpoints.removePending(request.id)
                userCancelled.remove(request.id)
                _jobs.update { it - request.id }
            }
        }
        // 앱이 강제로 꺼지거나 휴대폰이 다시 켜져 멈춘 작업은 이어서 한다.
        scope.launch(Dispatchers.IO) {
            val unfinished = checkpoints.pending().filter { File(it.path).exists() }
            unfinished.forEach { enqueue(it.id, File(it.path), it.title, it.auto) }
            if (unfinished.isNotEmpty()) _messages.tryEmit("멈췄던 자막 만들기를 이어서 합니다")
        }
    }

    /**
     * 자막 만들기를 대기열에 넣는다.
     * [auto] 는 다운로드 완료 후 자동으로 시작한 경우로, 모델이 없으면 Wi-Fi 등 무제한 네트워크에서만 받는다.
     */
    fun enqueue(id: Long, video: File, title: String, auto: Boolean = false) {
        if (id in _jobs.value) return
        _jobs.update { it + (id to Progress(Phase.WAITING, null)) }
        checkpoints.addPending(SubtitleCheckpoints.Pending(id, video.path, title, auto))
        queue.trySend(Request(id, video, title, auto))
        startService()
    }

    fun cancel(id: Long) {
        userCancelled += id
        checkpoints.delete(id)
        if (currentId == id) {
            WhisperNative.cancel()
            currentJob?.cancel()
        } else {
            checkpoints.removePending(id)
            userCancelled.remove(id)
            _jobs.update { it - id }
        }
    }

    fun cancelAll() {
        _jobs.value.keys.forEach(::cancel)
    }

    private suspend fun run(request: Request) {
        try {
            if (!model.isReady && request.auto && isMeteredNetwork()) {
                _messages.tryEmit("자막을 만들려면 음성 인식 모델(190MB)이 필요합니다. Wi-Fi 에서 「자막 만들기」를 눌러 주세요.")
                return
            }
            setProgress(request.id, Phase.MODEL, 0f)
            val modelFile = model.ensure { setProgress(request.id, Phase.MODEL, it) }

            val cues = recognize(request, modelFile)
            if (cues.cues.isEmpty()) throw IOException("말소리를 찾지 못했습니다")

            val korean = if (cues.isKorean) {
                cues.cues
            } else {
                setProgress(request.id, Phase.TRANSLATING, null)
                runCatching { SubtitleTranslator.toKorean(cues) }
                    .onFailure { Log.w(TAG, "자막 번역 실패", it) }
                    .getOrNull() ?: cues.cues
            }
            // 그 사이 영상이 지워졌으면 자막만 남기지 않는다.
            if (!request.video.exists()) return
            withContext(Dispatchers.IO) {
                Storage.subtitleFor(request.video).writeText(YouTubeSubtitles.toSrt(korean))
            }
            checkpoints.delete(request.id)
            _messages.tryEmit("자막을 만들었습니다: ${request.title}")
        } catch (e: CancellationException) {
            if (request.id in userCancelled) checkpoints.delete(request.id)
            _messages.tryEmit("자막 만들기를 취소했습니다")
        } catch (e: Throwable) {
            // 네이티브 라이브러리를 못 불러오는 기기(UnsatisfiedLinkError)도 여기서 알린다.
            Log.w(TAG, "자막 만들기 실패", e)
            _messages.tryEmit("자막 만들기 실패: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 인식할 조각 하나. [input] 이 null 이면 말소리가 없는 조각이다. */
    private class Chunk(val index: Int, val start: Double, val length: Double, val input: FloatArray?, val regions: SpeechRegions?)

    private suspend fun recognize(request: Request, modelFile: File): FetchedSubtitles = coroutineScope {
        // 이 휴대폰 CPU 에 맞는 가장 빠른 계산부(dotprod·fp16·int8 행렬 등)를 고른다.
        WhisperNative.loadBackends(context.applicationInfo.nativeLibraryDir)
        WhisperNative.resetCancel()
        // 설정에 따라 조각 2개를 동시에 인식한다(실험). 코어를 나눠 쓰고, 모델을 2개 불러 메모리를 더 쓴다.
        val workers = if (parallelMode()) 2 else 1
        val cores = Runtime.getRuntime().availableProcessors()
        val threads = (if (workers == 1) cores else cores / workers).coerceIn(1, 4)
        val contexts = mutableListOf<Long>()
        // 말소리 구간 감지(VAD). 못 불러오면 무음 건너뛰기 없이 전체를 인식한다.
        val vad = model.ensureVad()?.let { WhisperNative.vadInit(it.path, threads) } ?: 0L
        try {
            repeat(workers) {
                val ctx = WhisperNative.init(modelFile.path)
                if (ctx == 0L) throw IOException("음성 인식 모델을 불러오지 못했습니다")
                contexts += ctx
            }
            val total = AudioDecoder.durationSeconds(request.video).takeIf { it > 0 } ?: mediaDuration(request.video)
            // 지난번에 멈춘 곳이 있으면 이미 인식한 조각은 건너뛰고 이어서 한다.
            val saved = checkpoints.load(request.id, request.video)
            val resumeFrom = saved?.nextStart ?: 0.0
            val resumedFraction = if (total > 0) (resumeFrom / total).coerceIn(0.0, 1.0) else 0.0
            // 설정에 따라 소리를 1.5배속으로 줄여 인식할 분량을 더 줄인다.
            val speed = if (fastMode()) FAST_SPEED else 1f
            val startedAt = SystemClock.elapsedRealtime()
            setProgress(
                request.id, Phase.RECOGNIZING, if (total > 0) resumedFraction.toFloat() else null,
                if (saved != null) "이어서 하는 중" else "시작하는 중",
            )

            // 조각은 동시에 끝날 수 있으므로, 앞 조각부터 순서대로 모아 저장한다(이어서 하기 위치가 어긋나지 않게).
            val lock = Any()
            val committed = saved?.cues?.toMutableList() ?: mutableListOf()
            var committedEnd = resumeFrom
            var nextCommit = 0
            val finished = sortedMapOf<Int, Pair<Double, List<Cue>>>()
            val language = AtomicReference(saved?.language)
            var finishedSeconds = 0.0
            val active = arrayOfNulls<Chunk>(workers)

            fun commit(chunk: Chunk, cues: List<Cue>) = synchronized(lock) {
                finished[chunk.index] = (chunk.start + chunk.length) to cues
                finishedSeconds += chunk.length
                while (true) {
                    val (end, list) = finished.remove(nextCommit) ?: break
                    committed += list
                    committedEnd = end
                    nextCommit++
                }
                checkpoints.save(request.id, request.video, SubtitleCheckpoints.Progress(language.get(), committedEnd, committed.toList()))
            }

            // 인식은 네이티브에서 막혀 있으므로 진행률은 따로 읽어 갱신한다. 남은 시간도 지금까지 걸린 시간으로 어림한다.
            val poller = launch {
                while (isActive) {
                    val running = active.withIndex().sumOf { (slot, c) -> c?.let { it.length * WhisperNative.progress(slot) / 100.0 } ?: 0.0 }
                    val done = resumeFrom + synchronized(lock) { finishedSeconds } + running
                    if (total > 0) {
                        val fraction = (done / total).coerceIn(0.0, 1.0)
                        setProgress(request.id, Phase.RECOGNIZING, fraction.toFloat(), remainingText(startedAt, resumedFraction, fraction))
                    } else {
                        setProgress(request.id, Phase.RECOGNIZING, null, "${formatClock(done)} 처리함")
                    }
                    delay(1_000)
                }
            }

            val chunks = Channel<Chunk>(capacity = workers)
            val jobs = contexts.mapIndexed { slot, ctx ->
                launch(Dispatchers.Default) {
                    try {
                        for (chunk in chunks) {
                            val input = chunk.input
                            if (input == null) {
                                commit(chunk, emptyList()) // 말소리가 없는 조각
                                continue
                            }
                            active[slot] = chunk
                            // 앞 조각에서 감지한 언어를 이후 조각에도 써서 언어가 오락가락하지 않게 한다.
                            val result = WhisperNative.transcribe(ctx, input, threads, language.get() ?: "auto", slot)
                            active[slot] = null
                            if (result == CANCELLED) throw CancellationException()
                            if (result != 0) throw IOException("음성 인식에 실패했습니다 (오류 $result)")
                            language.compareAndSet(null, WhisperNative.language(ctx).ifBlank { null })
                            val cues = (0 until WhisperNative.segmentCount(ctx)).mapNotNull { i ->
                                val text = String(WhisperNative.segmentText(ctx, i), Charsets.UTF_8).trim()
                                if (text.isEmpty() || NOISE.matches(text)) return@mapNotNull null
                                // 배속을 되돌린 뒤, 이어 붙인 오디오의 시간을 이 조각의 원래 시간으로 바꾼다.
                                val t0 = WhisperNative.segmentStart(ctx, i) / 100.0 * speed
                                val t1 = WhisperNative.segmentEnd(ctx, i) / 100.0 * speed
                                val cueStart = chunk.start + (chunk.regions?.mapStart(t0) ?: t0)
                                val cueEnd = chunk.start + (chunk.regions?.mapEnd(t1) ?: t1)
                                if (cueEnd > cueStart) Cue(start = cueStart, end = cueEnd, text = text) else null
                            }
                            commit(chunk, cues)
                        }
                    } catch (e: Throwable) {
                        // 한 작업자가 실패하거나 취소되면 다른 작업자의 네이티브 인식도 바로 멈춘다.
                        WhisperNative.cancel()
                        throw e
                    }
                }
            }

            var index = 0
            AudioDecoder.decode(request.video, CHUNK_SECONDS) { samples, start ->
                val length = samples.size.toDouble() / AudioDecoder.SAMPLE_RATE
                if (start + length <= resumeFrom + 0.01) return@decode // 이미 인식한 조각
                // 무음·음악 구간은 건너뛴다: 말소리 구간만 이어 붙여 인식하고, 자막 시간은 대응표로 원래 시간으로 되돌린다.
                val regions = if (vad != 0L) {
                    val times = WhisperNative.vadSegments(vad, samples)
                    SpeechRegions.build((times.indices step 2).map { times[it].toDouble() to times[it + 1].toDouble() })
                } else {
                    null
                }
                val input = if (regions != null && regions.isEmpty) {
                    null
                } else {
                    SpeechTempo.speedUp(regions?.condense(samples, AudioDecoder.SAMPLE_RATE) ?: samples, speed)
                }
                chunks.send(Chunk(index++, start, length, input, regions))
            }
            chunks.close()
            jobs.joinAll()
            poller.cancel()
            FetchedSubtitles(language.get() ?: "ko", tidy(committed))
        } finally {
            contexts.forEach(WhisperNative::free)
            if (vad != 0L) WhisperNative.vadFree(vad)
        }
    }

    /**
     * 자막이 다음 말이 나올 때까지(무음 건너뛰기로 지운 긴 무음 동안) 계속 떠 있지 않도록,
     * 글자 수에 맞는 길이(최소 2초, 최대 12초)를 넘으면 자르고, 다음 자막과 겹치지 않게 한다.
     */
    private fun tidy(cues: List<Cue>): List<Cue> = cues.mapIndexed { i, cue ->
        val readable = (2.0 + cue.text.length * 0.1).coerceAtMost(12.0)
        val next = cues.getOrNull(i + 1)?.start ?: Double.MAX_VALUE
        cue.copy(end = minOf(cue.end, cue.start + readable, maxOf(next, cue.start + 0.5)))
    }

    private fun setProgress(id: Long, phase: Phase, fraction: Float?, detail: String? = null) {
        _jobs.update { if (id in it) it + (id to Progress(phase, fraction, detail)) else it }
    }

    /** 음성 트랙에 길이 정보가 없는 파일(TS 등)은 파일 전체 길이를 쓴다. 모르면 0. */
    private fun mediaDuration(file: File): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000.0) ?: 0.0
        } catch (_: Exception) {
            0.0
        } finally {
            retriever.release()
        }
    }

    /** 이번 실행에서 처리한 분량([from] → [fraction])과 걸린 시간으로 남은 시간을 어림한다(이어서 할 때도 맞게). */
    private fun remainingText(startedAt: Long, from: Double, fraction: Double): String {
        val done = fraction - from
        if (done < 0.02) return "남은 시간 계산 중"
        val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        val minutes = (elapsed * (1 - fraction) / done / 60).toInt()
        return if (minutes < 1) "1분 이내 남음" else "약 ${minutes}분 남음"
    }

    private fun formatClock(seconds: Double): String {
        val s = seconds.toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, SubtitleService::class.java))
        } catch (e: IllegalStateException) {
            // 백그라운드에서는 포그라운드 서비스를 시작할 수 없다. 작업은 프로세스가 살아 있는 동안 계속된다.
            Log.w(TAG, "자막 서비스를 시작할 수 없습니다", e)
        }
    }

    private fun isMeteredNetwork(): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    private companion object {
        const val TAG = "SubtitleGenerator"
        /** 2분씩 나눠 인식한다(조각이 작을수록 진행 표시가 자주 바뀌고 메모리도 덜 쓴다). */
        const val CHUNK_SECONDS = 120
        const val CANCELLED = -100
        const val FAST_SPEED = 1.5f

        /** 말이 없는 구간에 Whisper 가 넣는 표시([음악], (박수) 등)는 버린다. */
        val NOISE = Regex("""^[\[(（【][^\])）】]*[\])）】]$""")
    }
}
