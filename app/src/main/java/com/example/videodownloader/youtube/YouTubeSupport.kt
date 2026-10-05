package com.example.videodownloader.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException

/** 다운로드 창에 보여줄 유튜브 형식 하나. [spec] 은 다운로드 시점에 같은 형식을 다시 찾기 위한 키다. */
data class YtFormat(
    val spec: String,
    val label: String,
    val height: Int,
    val sizeBytes: Long,
    val ext: String,
)

data class YtVideo(
    val watchUrl: String,
    val title: String,
    val durationSec: Double,
    val thumbnailUrl: String?,
    val formats: List<YtFormat>,
)

/** 실제로 받을 스트림 주소와(알면) 크기. */
data class YtStreamRef(val url: String, val contentLength: Long)

data class YtStreams(val video: YtStreamRef?, val audio: YtStreamRef?) {
    val isAudioOnly: Boolean get() = video == null
}

/**
 * 형식 키.
 * - `v=<itag>;a=<itag>` : 영상 전용 + 음성 전용을 받아 MP4 로 합친다(최대 1080p, H.264+AAC).
 * - `p=<itag>`          : 영상·음성이 함께 든 단일 파일(보통 360p).
 * - `a=<itag>`          : 음성만(M4A).
 */
data class YtSpec(val videoItag: Int?, val audioItag: Int?, val progressiveItag: Int?) {
    fun encode(): String = when {
        progressiveItag != null -> "p=$progressiveItag"
        videoItag != null -> "v=$videoItag;a=$audioItag"
        else -> "a=$audioItag"
    }

    companion object {
        fun parse(value: String): YtSpec {
            val parts = value.split(';').mapNotNull {
                val (k, v) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
                k.trim() to v.trim().toIntOrNull()
            }.toMap()
            return YtSpec(parts["v"], parts["a"], parts["p"])
        }
    }
}

object YouTubeSupport {
    const val BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    @Volatile
    private var initialized = false

    fun init(client: OkHttpClient) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            NewPipe.init(NewPipeHttpDownloader(client), Localization("ko", "KR"), ContentCountry("KR"))
            initialized = true
        }
    }

    /** youtube.com/watch?v=, /shorts/, /live/, /embed/, youtu.be/ 주소에서 영상 ID 를 꺼낸다. */
    fun videoId(url: String): String? {
        val u = url.toHttpUrlOrNull() ?: return null
        val host = u.host.lowercase().removePrefix("www.").removePrefix("m.").removePrefix("music.")
        val segments = u.pathSegments.filter { it.isNotEmpty() }
        val id = when (host) {
            "youtube.com", "youtube-nocookie.com" -> when {
                segments.firstOrNull() == "watch" -> u.queryParameter("v")
                segments.firstOrNull() in setOf("shorts", "live", "embed", "v") -> segments.getOrNull(1)
                else -> null
            }
            "youtu.be" -> segments.firstOrNull()
            else -> null
        }
        return id?.takeIf { VIDEO_ID.matches(it) }
    }

    fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    suspend fun resolve(url: String): YtVideo = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        val audio = bestAudio(info.audioStreams)
        val videos = videoOnlyCandidates(info.videoOnlyStreams)

        val formats = mutableListOf<YtFormat>()
        if (audio != null) {
            videos.forEach { v ->
                formats += YtFormat(
                    spec = YtSpec(v.itag, audio.itag, null).encode(),
                    label = "${v.height}P",
                    height = v.height,
                    sizeBytes = sumLengths(v, audio),
                    ext = "mp4",
                )
            }
        }
        val covered = formats.map { it.height }.toSet()
        info.videoStreams
            .filter { it.isDownloadable() && it.format == MediaFormat.MPEG_4 && it.height !in covered }
            .forEach { p ->
                formats += YtFormat(YtSpec(null, null, p.itag).encode(), "${p.height}P", p.height, p.length(), "mp4")
            }
        if (audio != null) {
            formats += YtFormat(YtSpec(null, audio.itag, null).encode(), "음성 M4A", 0, audio.length(), "m4a")
        }
        if (formats.isEmpty()) throw IOException("받을 수 있는 형식이 없습니다(실시간 방송이거나 제한된 영상일 수 있습니다)")

        YtVideo(
            watchUrl = url,
            title = info.name,
            durationSec = info.duration.toDouble(),
            thumbnailUrl = info.thumbnails.maxByOrNull { it.height }?.url,
            formats = formats.sortedByDescending { it.height },
        )
    }

    /** 다운로드 시작 때마다 새로 조회한다(스트림 주소는 몇 시간 뒤 만료된다). */
    suspend fun resolveStreams(url: String, spec: YtSpec): YtStreams = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        spec.progressiveItag?.let { itag ->
            val stream = info.videoStreams.firstOrNull { it.itag == itag && it.isDownloadable() }
                ?: info.videoStreams.filter { it.isDownloadable() && it.format == MediaFormat.MPEG_4 }.maxByOrNull { it.height }
                ?: throw IOException("선택한 화질을 더 이상 받을 수 없습니다")
            return@withContext YtStreams(YtStreamRef(stream.content, stream.length()), null)
        }
        val audio = info.audioStreams.firstOrNull { it.itag == spec.audioItag && it.isDownloadable() }
            ?: bestAudio(info.audioStreams)
            ?: throw IOException("음성 스트림을 찾지 못했습니다")
        val video = spec.videoItag?.let { itag ->
            info.videoOnlyStreams.firstOrNull { it.itag == itag && it.isDownloadable() }
                ?: throw IOException("선택한 화질을 더 이상 받을 수 없습니다")
        }
        YtStreams(video?.let { YtStreamRef(it.content, it.length()) }, YtStreamRef(audio.content, audio.length()))
    }

    /** 스트림 주소를 받은 클라이언트 종류에 맞는 헤더. 맞지 않으면 403 이 나거나 속도가 크게 제한된다. */
    fun streamHeaders(url: String): Map<String, String> = when {
        YoutubeParsingHelper.isAndroidStreamingUrl(url) ->
            mapOf("User-Agent" to YoutubeParsingHelper.getAndroidUserAgent(null))
        YoutubeParsingHelper.isIosStreamingUrl(url) ->
            mapOf("User-Agent" to YoutubeParsingHelper.getIosUserAgent(null))
        YoutubeParsingHelper.isVisionOsStreamingUrl(url) ->
            mapOf("User-Agent" to YoutubeParsingHelper.getVisionOsUserAgent(null))
        else -> mapOf(
            "User-Agent" to BROWSER_USER_AGENT,
            "Origin" to "https://www.youtube.com",
            "Referer" to "https://www.youtube.com/",
        )
    }

    private fun Stream.isDownloadable() = isUrl && deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP

    private fun Stream.length(): Long = itagItem?.contentLength?.takeIf { it > 0 } ?: -1L

    private fun sumLengths(a: Stream, b: Stream): Long {
        val x = a.length()
        val y = b.length()
        return if (x > 0 && y > 0) x + y else -1L
    }

    /** 기본(원래 언어) 음성 트랙 중 비트레이트가 가장 높은 AAC(M4A). */
    private fun bestAudio(streams: List<AudioStream>): AudioStream? =
        streams
            .filter { it.isDownloadable() && it.format == MediaFormat.M4A }
            .filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .ifEmpty { streams.filter { it.isDownloadable() && it.format == MediaFormat.M4A } }
            .maxByOrNull { if (it.averageBitrate > 0) it.averageBitrate else it.bitrate }

    /** 휴대폰에서 바로 재생·합치기 쉬운 H.264(MP4) 영상만, 화질별로 하나씩. */
    private fun videoOnlyCandidates(streams: List<VideoStream>): List<VideoStream> =
        streams
            .filter { it.isDownloadable() && it.format == MediaFormat.MPEG_4 && it.codec?.startsWith("avc1") == true }
            .groupBy { it.height }
            .map { (_, list) -> list.maxBy { it.bitrate } }
            .sortedByDescending { it.height }
}
