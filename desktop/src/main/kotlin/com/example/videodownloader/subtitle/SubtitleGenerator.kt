package com.example.videodownloader.subtitle

import com.example.videodownloader.download.Ffmpeg
import com.example.videodownloader.util.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 「자막 만들기」(Android 판과 같은 기능): 영상의 말소리를 PC 에서 인식(whisper.cpp)하고,
 * 한국어가 아니면 한국어로 번역해 영상 옆에 같은 이름의 .srt 로 저장한다. 한 번에 하나씩 차례로 처리한다.
 * 순서: ffmpeg 로 소리만 16kHz 모노 WAV 로 뽑기 → whisper-cli 로 인식(SRT) → 번역 → 저장.
 */
class SubtitleGenerator(
    private val ffmpeg: Ffmpeg,
    val tools: WhisperTools,
    private val translator: SubtitleTranslator,
    private val workDir: File,
    private val scope: CoroutineScope,
    /** 설정의 「빠른 자막 만들기(1.5배속)」 */
    private val fastMode: () -> Boolean = { false },
) {
    /** [fraction] 은 0..1, 모르면 null. */
    data class Progress(val label: String, val fraction: Float?)

    private data class Request(val id: Long, val video: File, val title: String)

    private val _jobs = MutableStateFlow<Map<Long, Progress>>(emptyMap())

    /** 다운로드 ID → 진행 상태. 대기·진행 중인 것만 들어 있다. */
    val jobs: StateFlow<Map<Long, Progress>> = _jobs.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val queue = Channel<Request>(Channel.UNLIMITED)

    @Volatile
    private var currentId: Long? = null

    @Volatile
    private var currentJob: Job? = null

    init {
        scope.launch {
            for (request in queue) {
                if (request.id !in _jobs.value) continue // 대기 중에 취소됨
                currentId = request.id
                val job = launch(Dispatchers.IO) { run(request) }
                currentJob = job
                job.join()
                currentId = null
                currentJob = null
                _jobs.update { it - request.id }
            }
        }
    }

    fun enqueue(id: Long, video: File, title: String) {
        if (id in _jobs.value) return
        _jobs.update { it + (id to Progress("자막 대기 중", null)) }
        queue.trySend(Request(id, video, title))
    }

    fun cancel(id: Long) {
        if (currentId == id) currentJob?.cancel() else _jobs.update { it - id }
    }

    private suspend fun run(request: Request) {
        val wav = File(workDir, "subtitle-${request.id}.wav")
        val outBase = File(workDir, "subtitle-${request.id}")
        val condensed = File(workDir, "subtitle-${request.id}-speech.wav")
        val fast = File(workDir, "subtitle-${request.id}-fast.wav")
        try {
            if (!request.video.isFile) throw IOException("파일이 없습니다")
            set(request.id, "준비 중", null)
            ffmpeg.ensure { done, total -> set(request.id, "ffmpeg 받는 중", if (total > 0) done.toFloat() / total else null) }
            val (cli, model) = tools.ensure { label, fraction -> set(request.id, label, fraction) }

            val vad = tools.ensureVad()
            val speed = if (fastMode()) FAST_SPEED else 1f
            set(request.id, "소리 추출 중", null)
            workDir.mkdirs()
            ffmpeg.extractSpeechAudio(request.video, wav)

            // 무음·음악 구간은 건너뛴다: 말소리 구간만 이어 붙여 인식하고, 자막 시간은 대응표로 원래 시간으로 되돌린다.
            set(request.id, "말소리 구간 찾는 중", null)
            val regions = vad?.let { tools.speechRegions(wav, it) }?.let { SpeechRegions.build(it) }
            if (regions != null && regions.isEmpty) throw IOException("말소리를 찾지 못했습니다")
            if (regions != null) Wav.write(condensed, regions.condense(Wav.read(wav), SAMPLE_RATE)) else wav.copyTo(condensed, true)
            // 설정에 따라 소리를 1.5배속으로 줄여 인식할 분량을 더 줄인다(음 높이는 그대로).
            val input = if (speed != 1f) fast.also { ffmpeg.extractSpeechAudio(condensed, it, speed) } else condensed

            set(request.id, "음성 인식 중", 0f)
            val language = recognize(request.id, cli, model, input, outBase)
            val cues = Srt.parse(File(outBase.path + ".srt").readText())
                .filterNot { NOISE.matches(it.text.trim()) }
                .map { cue ->
                    // 배속을 되돌린 뒤, 이어 붙인 오디오의 시간을 원래 영상 시간으로 바꾼다.
                    val start = cue.start * speed
                    val end = cue.end * speed
                    if (regions == null) cue.copy(start = start, end = end)
                    else cue.copy(start = regions.mapStart(start), end = regions.mapEnd(end))
                }
                .filter { it.end > it.start }
                .let(Srt::tidy)
            if (cues.isEmpty()) throw IOException("말소리를 찾지 못했습니다")

            val korean = if (language.startsWith("ko")) {
                cues
            } else {
                set(request.id, "한국어로 번역 중", 0f)
                runCatching { translator.toKorean(cues, language) { set(request.id, "한국어로 번역 중", it) } }
                    .getOrElse { e ->
                        if (e is CancellationException) throw e
                        _messages.tryEmit("번역하지 못해 원래 언어 자막으로 저장합니다: ${e.message}")
                        cues
                    }
            }
            if (!request.video.exists()) return // 그 사이 영상이 지워졌으면 자막만 남기지 않는다.
            Storage.subtitleFor(request.video).writeText(Srt.write(korean))
            _messages.tryEmit("자막을 만들었습니다: ${request.title}")
        } catch (e: CancellationException) {
            _messages.tryEmit("자막 만들기를 취소했습니다")
        } catch (e: Exception) {
            _messages.tryEmit("자막 만들기 실패: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            listOf(wav, condensed, fast, File(outBase.path + ".srt")).forEach { it.delete() }
        }
    }

    /** whisper-cli 를 돌려 [outBase].srt 를 만들고, 감지한 언어 코드를 돌려준다. */
    private suspend fun recognize(id: Long, cli: File, model: File, wav: File, outBase: File): String =
        withContext(Dispatchers.IO) {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
            val args = listOf(
                cli.path, "-m", model.path, "-f", wav.path, "-l", "auto", "-t", threads.toString(),
                "-pp", "-osrt", "-of", outBase.path,
                // 앞 문장을 다음 구간의 힌트로 넘기지 않는다(같은 문장이 되풀이되는 일을 막는다).
                "-mc", "0",
            )
            val process = ProcessBuilder(args).directory(cli.parentFile).redirectErrorStream(true).start()
            var language = ""
            var lastLine = ""
            try {
                val reader = launch {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            lastLine = line.takeIf { it.isNotBlank() } ?: lastLine
                            PROGRESS.find(line)?.let { set(id, "음성 인식 중", it.groupValues[1].toInt() / 100f) }
                            LANGUAGE.find(line)?.let { language = it.groupValues[1] }
                        }
                    }
                }
                val code = runInterruptible { process.waitFor() }
                reader.join()
                if (code != 0) throw IOException("음성 인식에 실패했습니다($code) $lastLine".trim())
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            language
        }

    private fun set(id: Long, label: String, fraction: Float?) {
        _jobs.update { jobs ->
            val old = jobs[id] ?: return@update jobs
            // 받기 진행률은 조각마다 오므로 1% 이상 바뀔 때만 화면을 갱신한다.
            val same = old.label == label && old.fraction != null && fraction != null &&
                kotlin.math.abs(old.fraction - fraction) < 0.01f && fraction < 1f
            if (same) jobs else jobs + (id to Progress(label, fraction))
        }
    }

    private companion object {
        const val FAST_SPEED = 1.5f
        const val SAMPLE_RATE = 16_000
        val PROGRESS = Regex("""progress\s*=\s*(\d+)%""")
        val LANGUAGE = Regex("""auto-detected language:\s*([a-z-]+)""")

        /** 말이 없는 구간에 Whisper 가 넣는 표시([음악], (박수) 등)는 버린다. */
        val NOISE = Regex("""^[\[(（【][^\])）】]*[\])）】]$""")
    }
}
