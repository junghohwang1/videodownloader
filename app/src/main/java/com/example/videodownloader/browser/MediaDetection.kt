package com.example.videodownloader.browser

import android.net.Uri
import android.webkit.MimeTypeMap
import com.example.videodownloader.data.db.MediaKind
import com.example.videodownloader.download.HlsParser
import com.example.videodownloader.download.HlsVariant
import com.example.videodownloader.download.applyMediaHeaders
import com.example.videodownloader.download.executeCancellable
import com.example.videodownloader.download.parseContentRangeTotal
import com.example.videodownloader.util.Storage
import com.example.videodownloader.youtube.YtFormat
import okhttp3.OkHttpClient
import okhttp3.Request

/** 브라우저에서 감지한 미디어 후보. */
data class DetectedMedia(
    val url: String,
    val kind: MediaKind,
    val pageUrl: String?,
    val pageTitle: String?,
    val referer: String?,
    val userAgent: String?,
    val mimeType: String? = null,
    val sizeBytes: Long = -1,
    val durationSec: Double = 0.0,
    val variantCount: Int = 0,
    val resolution: String? = null,
    /** 마스터 플레이리스트가 가리키는 화질별 플레이리스트. 목록에서 중복 표시하지 않는다. */
    val variantUrls: List<String> = emptyList(),
    /** 마스터 플레이리스트의 화질 목록(대역폭·해상도). 다운로드 창에서 화질을 고를 때 쓴다. */
    val variants: List<HlsVariant> = emptyList(),
    /** 유튜브 영상의 형식 목록. */
    val ytFormats: List<YtFormat> = emptyList(),
    val thumbnailUrl: String? = null,
    /** 정보 조회 실패 사유(다운로드 창에 표시). */
    val error: String? = null,
    /** Content-Disposition 등으로 서버가 알려준 파일명. */
    val suggestedName: String? = null,
    val probed: Boolean = false,
) {
    val displayName: String
        get() = (if (kind == MediaKind.YOUTUBE) pageTitle else null)
            ?: suggestedName
            ?: Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() }
            ?: url
}

object MediaSniffer {
    private val FILE_EXTENSIONS = setOf("mp4", "m4v", "webm", "mov", "mkv", "3gp", "flv", "avi", "wmv")
    private val HLS_MIME = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
    )

    /** 세그먼트 조각(.ts, .m4s)은 제외하고 완결된 파일·플레이리스트만 고른다. */
    fun classifyUrl(url: String): MediaKind? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (uri.scheme != "http" && uri.scheme != "https") return null
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: return null
        return when (ext) {
            "m3u8" -> MediaKind.HLS
            in FILE_EXTENSIONS -> MediaKind.FILE
            else -> null
        }
    }

    fun classifyMime(mimeType: String?): MediaKind? {
        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim() ?: return null
        return when {
            mime in HLS_MIME -> MediaKind.HLS
            mime.startsWith("video/") && mime != "video/mp2t" && mime != "video/iso.segment" -> MediaKind.FILE
            else -> null
        }
    }

    fun extensionFromUrl(url: String): String? =
        Uri.parse(url).lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }

    fun suggestFileName(media: DetectedMedia): String {
        val ext = when (media.kind) {
            MediaKind.HLS -> "ts"
            MediaKind.YOUTUBE -> "mp4"
            MediaKind.FILE -> media.suggestedName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
                ?: extensionFromUrl(media.url)?.takeIf { it in FILE_EXTENSIONS }
                ?: media.mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?: "mp4"
        }
        val base = media.suggestedName?.substringBeforeLast('.')
            ?: media.pageTitle?.takeIf { it.isNotBlank() }
            ?: media.displayName.substringBeforeLast('.')
        return Storage.sanitizeFileName("$base.$ext")
    }
}

/** 감지된 URL 에 가볍게 요청해 크기·형식·화질 정보를 채운다. */
object MediaProbe {

    suspend fun probe(client: OkHttpClient, media: DetectedMedia): DetectedMedia = when (media.kind) {
        MediaKind.FILE -> probeFile(client, media)
        MediaKind.HLS -> probeHls(client, media)
        MediaKind.YOUTUBE -> media
    }

    private suspend fun probeFile(client: OkHttpClient, media: DetectedMedia): DetectedMedia {
        val request = Request.Builder()
            .url(media.url)
            .applyMediaHeaders(media.url, media.userAgent, media.referer)
            .header("Range", "bytes=0-0")
            .build()
        val (mime, size) = client.executeCancellable(request) { response ->
            if (!response.isSuccessful) return@executeCancellable null to -1L
            val type = response.body.contentType()?.let { "${it.type}/${it.subtype}" }
            val length = parseContentRangeTotal(response.header("Content-Range"))
                ?: response.body.contentLength()
            type to length
        }
        if (MediaSniffer.classifyMime(mime) == MediaKind.HLS) {
            return probeHls(client, media.copy(kind = MediaKind.HLS))
        }
        return media.copy(mimeType = mime ?: media.mimeType, sizeBytes = size, probed = true)
    }

    private suspend fun probeHls(client: OkHttpClient, media: DetectedMedia): DetectedMedia {
        val text = fetchText(client, media.url, media) ?: return media.copy(probed = true)
        if (!HlsParser.isMaster(text)) {
            val playlist = HlsParser.parseMedia(text, media.url)
            return media.copy(durationSec = playlist.durationSec, probed = true)
        }
        val variants = HlsParser.parseMaster(text, media.url)
        val best = variants.maxByOrNull { it.bandwidth }
        val duration = best?.let { fetchText(client, it.url, media) }
            ?.let { HlsParser.parseMedia(it, best.url).durationSec }
            ?: 0.0
        return media.copy(
            variantCount = variants.size,
            resolution = best?.resolution,
            variantUrls = variants.map { it.url },
            variants = variants,
            durationSec = duration,
            probed = true,
        )
    }

    private suspend fun fetchText(client: OkHttpClient, url: String, media: DetectedMedia): String? {
        val request = Request.Builder()
            .url(url)
            .applyMediaHeaders(url, media.userAgent, media.referer)
            .build()
        return client.executeCancellable(request) { response ->
            if (response.isSuccessful) response.body.string() else null
        }
    }
}
