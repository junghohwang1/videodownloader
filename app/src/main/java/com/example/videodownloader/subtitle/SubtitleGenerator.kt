package com.example.videodownloader.subtitle

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/**
 * 「자막 만들기」: 영상의 말소리를 기기 안에서 인식(whisper.cpp)하고, 한국어가 아니면 한국어로 번역해
 * 영상 옆에 .srt 로 저장한다. 무거운 작업이라 한 번에 하나씩 차례로 처리한다.
 */
class SubtitleGenerator(
    private val context: Context,
    client: OkHttpClient,
    private val scope: CoroutineScope,
) {
    enum class Phase(val label: String) {
        WAITING("자막 대기 중"),
        MODEL("음성 인식 모델 받는 중"),
        RECOGNIZING("음성 인식 중"),
        TRANSLATING("한국어로 번역 중"),
    }

    data class Progress(val phase: Phase, val fraction: Float?)

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
                _jobs.update { it - request.id }
            }
        }
    }

    /**
     * 자막 만들기를 대기열에 넣는다.
     * [auto] 는 다운로드 완료 후 자동으로 시작한 경우로, 모델이 없으면 Wi-Fi 등 무제한 네트워크에서만 받는다.
     */
    fun enqueue(id: Long, video: File, title: String, auto: Boolean = false) {
        if (id in _jobs.value) return
        _jobs.update { it + (id to Progress(Phase.WAITING, null)) }
        queue.trySend(Request(id, video, title, auto))
        startService()
    }

    fun cancel(id: Long) {
        if (currentId == id) {
            WhisperNative.cancel()
            currentJob?.cancel()
        } else {
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
            _messages.tryEmit("자막을 만들었습니다: ${request.title}")
        } catch (e: CancellationException) {
            _messages.tryEmit("자막 만들기를 취소했습니다")
        } catch (e: Throwable) {
            // 네이티브 라이브러리를 못 불러오는 기기(UnsatisfiedLinkError)도 여기서 알린다.
            Log.w(TAG, "자막 만들기 실패", e)
            _messages.tryEmit("자막 만들기 실패: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun recognize(request: Request, modelFile: File): FetchedSubtitles = coroutineScope {
        val ctx = WhisperNative.init(modelFile.path)
        if (ctx == 0L) throw IOException("음성 인식 모델을 불러오지 못했습니다")
        try {
            val total = AudioDecoder.durationSeconds(request.video)
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            val cues = mutableListOf<Cue>()
            var language: String? = null
            setProgress(request.id, Phase.RECOGNIZING, 0f)

            AudioDecoder.decode(request.video, CHUNK_SECONDS) { samples, start ->
                val length = samples.size.toDouble() / AudioDecoder.SAMPLE_RATE
                // 인식은 네이티브에서 막혀 있으므로 진행률은 따로 읽어 갱신한다.
                val poller = launch {
                    while (isActive) {
                        if (total > 0) {
                            val done = start + length * WhisperNative.progress() / 100.0
                            setProgress(request.id, Phase.RECOGNIZING, (done / total).toFloat().coerceIn(0f, 1f))
                        }
                        delay(500)
                    }
                }
                try {
                    // 첫 조각에서 감지한 언어를 이후 조각에도 써서 언어가 오락가락하지 않게 한다.
                    val result = WhisperNative.transcribe(ctx, samples, threads, language ?: "auto")
                    if (result == CANCELLED) throw CancellationException()
                    if (result != 0) throw IOException("음성 인식에 실패했습니다 (오류 $result)")
                } finally {
                    poller.cancel()
                }
                if (language == null) language = WhisperNative.language(ctx).ifBlank { null }
                for (i in 0 until WhisperNative.segmentCount(ctx)) {
                    val text = String(WhisperNative.segmentText(ctx, i), Charsets.UTF_8).trim()
                    if (text.isEmpty() || NOISE.matches(text)) continue
                    cues += Cue(
                        start = start + WhisperNative.segmentStart(ctx, i) / 100.0,
                        end = start + WhisperNative.segmentEnd(ctx, i) / 100.0,
                        text = text,
                    )
                }
            }
            FetchedSubtitles(language ?: "ko", cues)
        } finally {
            WhisperNative.free(ctx)
        }
    }

    private fun setProgress(id: Long, phase: Phase, fraction: Float?) {
        _jobs.update { if (id in it) it + (id to Progress(phase, fraction)) else it }
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
        const val CHUNK_SECONDS = 300
        const val CANCELLED = -100

        /** 말이 없는 구간에 Whisper 가 넣는 표시([음악], (박수) 등)는 버린다. */
        val NOISE = Regex("""^[\[(（【][^\])）】]*[\])）】]$""")
    }
}
