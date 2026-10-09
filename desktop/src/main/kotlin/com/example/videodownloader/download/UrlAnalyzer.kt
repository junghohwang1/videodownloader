package com.example.videodownloader.download

import com.example.videodownloader.data.MediaKind
import com.example.videodownloader.util.Storage
import com.example.videodownloader.youtube.YouTubeSupport
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** 다운로드 창에 보여줄 선택지 하나. */
data class DownloadOption(
    val label: String,
    val detail: String,
    val kind: MediaKind,
    val url: String,
    val ext: String,
    val sizeBytes: Long = -1,
    val formatSpec: String? = null,
    val height: Int = 0,
)

data class AnalysisResult(
    val title: String,
    val pageUrl: String,
    val referer: String?,
    val thumbnailUrl: String? = null,
    val durationSec: Double = 0.0,
    val options: List<DownloadOption>,
)

/**
 * 붙여넣은 주소를 분석해 받을 수 있는 형식을 찾는다(내장 브라우저 없이 쓰는 1단계 진입점).
 * - 유튜브 주소 → NewPipe Extractor 로 화질 목록
 * - m3u8 → 마스터면 화질별 선택지
 * - 영상 파일 → 크기 확인
 * - 일반 웹페이지 → HTML 안의 og:video·<video>·mp4/m3u8 주소를 찾아 위 과정을 반복
 */
class UrlAnalyzer(private val client: OkHttpClient) {

    suspend fun analyze(input: String): AnalysisResult {
        val url = normalize(input) ?: throw IOException("올바른 주소가 아닙니다")

        YouTubeSupport.videoId(url)?.let { id ->
            val video = YouTubeSupport.resolve(YouTubeSupport.watchUrl(id))
            return AnalysisResult(
                title = video.title,
                pageUrl = video.watchUrl,
                referer = null,
                thumbnailUrl = video.thumbnailUrl,
                durationSec = video.durationSec,
                options = video.formats.map {
                    DownloadOption(
                        label = it.label,
                        detail = if (it.ext == "m4a") "M4A" else "MP4",
                        kind = MediaKind.YOUTUBE,
                        url = video.watchUrl,
                        ext = it.ext,
                        sizeBytes = it.sizeBytes,
                        formatSpec = it.spec,
                        height = it.height,
                    )
                },
            )
        }

        val head = fetchHead(url, referer = null)
        return when {
            head.isHls -> {
                val (options, duration) = hlsOptions(url, head.text ?: fetchText(url, null), null)
                AnalysisResult(fileTitle(url), url, null, durationSec = duration, options = options)
            }
            head.isVideo -> AnalysisResult(
                title = head.fileName?.substringBeforeLast('.') ?: fileTitle(url),
                pageUrl = url,
                referer = null,
                options = listOf(fileOption(url, head)),
            )
            head.isHtml -> analyzePage(url, head.text.orEmpty())
            else -> throw IOException("영상을 찾지 못했습니다 (${head.mime ?: "알 수 없는 형식"})")
        }
    }

    private suspend fun analyzePage(pageUrl: String, html: String): AnalysisResult {
        val title = metaContent(html, "og:title") ?: TITLE.find(html)?.groupValues?.get(1)?.let(::unescape)?.trim()
            ?: fileTitle(pageUrl)
        val thumbnail = metaContent(html, "og:image")?.let { HlsParser.resolve(pageUrl, it) }

        // 페이지 안에 유튜브 영상이 박혀 있으면 그쪽으로 넘긴다.
        if (findCandidates(html, pageUrl).isEmpty()) {
            EMBED_YOUTUBE.find(html)?.groupValues?.get(1)?.let { id ->
                return analyze(YouTubeSupport.watchUrl(id))
            }
            throw IOException("페이지에서 영상을 찾지 못했습니다. 스트리밍 전용(blob) 사이트는 2단계 내장 브라우저에서 지원합니다.")
        }

        val candidates = findCandidates(html, pageUrl).take(MAX_CANDIDATES)
        val options = coroutineScope {
            candidates.map { candidate ->
                async {
                    runCatching {
                        val head = fetchHead(candidate, referer = pageUrl)
                        when {
                            head.isHls -> hlsOptions(candidate, head.text ?: fetchText(candidate, pageUrl), pageUrl).first
                            head.isVideo -> listOf(fileOption(candidate, head))
                            else -> emptyList()
                        }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        // 마스터가 가리키는 하위 플레이리스트를 페이지에서도 따로 찾았다면 한 번만 보여준다.
        val unique = options.distinctBy { it.url }.sortedWith(compareByDescending<DownloadOption> { it.height }.thenByDescending { it.sizeBytes })
        if (unique.isEmpty()) throw IOException("페이지의 영상 주소에 접근할 수 없습니다")
        return AnalysisResult(title, pageUrl, pageUrl, thumbnailUrl = thumbnail, options = unique)
    }

    private suspend fun hlsOptions(url: String, text: String, referer: String?): Pair<List<DownloadOption>, Double> {
        if (!HlsParser.isMaster(text)) {
            val playlist = HlsParser.parseMedia(text, url)
            return listOf(DownloadOption("HLS", "MP4 변환", MediaKind.HLS, url, "mp4")) to playlist.durationSec
        }
        val variants = HlsParser.parseMaster(text, url).sortedByDescending { it.bandwidth }
        if (variants.isEmpty()) throw IOException("재생 가능한 화질 정보를 찾지 못했습니다")
        val duration = runCatching { HlsParser.parseMedia(fetchText(variants.first().url, referer), variants.first().url).durationSec }
            .getOrDefault(0.0)
        val options = variants.distinctBy { it.resolution ?: it.bandwidth.toString() }.map { v ->
            val height = v.resolution?.substringAfter('x')?.toIntOrNull() ?: 0
            val estimated = if (duration > 0 && v.bandwidth > 0) (v.bandwidth / 8 * duration).toLong() else -1L
            DownloadOption(
                label = if (height > 0) "${height}P" else "${v.bandwidth / 1000} kbps",
                detail = "HLS → MP4",
                kind = MediaKind.HLS,
                url = v.url,
                ext = "mp4",
                sizeBytes = estimated,
                height = height,
            )
        }
        return options to duration
    }

    private fun fileOption(url: String, head: Head): DownloadOption {
        val ext = head.fileName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 5 }
            ?: extensionFromUrl(url)?.takeIf { it in FILE_EXTENSIONS }
            ?: Storage.extensionForMime(head.mime)
            ?: "mp4"
        val height = HEIGHT_IN_URL.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return DownloadOption(
            label = if (height > 0) "${height}P" else ext.uppercase(),
            // 같은 형식이 여러 개면 구분할 수 있게 파일 이름을 함께 보여준다.
            detail = head.fileName ?: url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.takeIf { it.isNotBlank() } ?: ext.uppercase(),
            kind = MediaKind.FILE,
            url = url,
            ext = ext,
            sizeBytes = head.size,
            height = height,
        )
    }

    private class Head(val mime: String?, val size: Long, val fileName: String?, val text: String?) {
        val isHls get() = mime in HLS_MIME || text?.trimStart()?.startsWith("#EXTM3U") == true
        val isVideo get() = !isHls && mime != null && (mime.startsWith("video/") || mime.startsWith("audio/")) &&
            mime != "video/mp2t" && mime != "video/iso.segment"
        val isHtml get() = mime == "text/html" || mime == "application/xhtml+xml"
    }

    /** 첫 응답의 헤더만 보고, 텍스트(HTML·m3u8)일 때만 본문을 읽는다. 영상 본문은 읽지 않고 끊는다. */
    private suspend fun fetchHead(url: String, referer: String?): Head {
        val request = Request.Builder().url(url).applyMediaHeaders(url, null, referer).build()
        return client.executeCancellable(request) { response ->
            if (!response.isSuccessful) throw IOException("서버 응답 오류 (HTTP ${response.code})")
            val mime = response.body.contentType()?.let { "${it.type}/${it.subtype}".lowercase() }
                ?: if (extensionFromUrl(url) == "m3u8") "application/vnd.apple.mpegurl" else null
            val looksText = mime == null || mime.startsWith("text/") || mime in HLS_MIME ||
                mime == "application/xhtml+xml" || mime == "application/octet-stream" && extensionFromUrl(url) == "m3u8"
            val text = if (looksText) readLimited(response.body.byteStream()) else null
            Head(
                mime = mime,
                size = response.body.contentLength(),
                fileName = contentDispositionName(response.header("Content-Disposition")),
                text = text,
            )
        }
    }

    private suspend fun fetchText(url: String, referer: String?): String {
        val request = Request.Builder().url(url).applyMediaHeaders(url, null, referer).build()
        return client.executeCancellable(request) { response ->
            if (!response.isSuccessful) throw IOException("서버 응답 오류 (HTTP ${response.code})")
            response.body.string()
        }
    }

    private fun readLimited(input: java.io.InputStream): String {
        val bytes = input.readNBytes(MAX_TEXT_BYTES)
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        private const val MAX_CANDIDATES = 12
        private const val MAX_TEXT_BYTES = 4 * 1024 * 1024

        private val FILE_EXTENSIONS = setOf("mp4", "m4v", "webm", "mov", "mkv", "3gp", "flv", "avi", "wmv", "m4a", "mp3")
        private val HLS_MIME = setOf(
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "audio/mpegurl",
            "audio/x-mpegurl",
        )
        private val TITLE = Regex("<title[^>]*>([^<]{1,300})</title>", RegexOption.IGNORE_CASE)
        private val MEDIA_URL = Regex(
            // JSON 안에서는 슬래시가 \/ 로 이스케이프되어 있기도 하다.
            "(?:https?:)?(?://|\\\\/\\\\/)[^\\s\"'<>]+?\\.(?:mp4|m4v|webm|mov|m3u8)(?:\\?[^\\s\"'<>]*)?",
            RegexOption.IGNORE_CASE,
        )
        private val SRC_ATTR = Regex(
            "<(?:video|source)[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE,
        )
        private val EMBED_YOUTUBE = Regex("(?:youtube(?:-nocookie)?\\.com/embed/|youtu\\.be/)([A-Za-z0-9_-]{11})")
        private val HEIGHT_IN_URL = Regex("(?<![0-9])(2160|1440|1080|720|540|480|360|240)p?(?![0-9])", RegexOption.IGNORE_CASE)

        fun normalize(input: String): String? {
            val trimmed = input.trim().takeIf { it.isNotEmpty() } ?: return null
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
            return withScheme.toHttpUrlOrNull()?.toString()
        }

        /** HTML(및 그 안의 JSON)에서 영상 주소 후보를 찾는다. 세그먼트 조각(.ts, .m4s)은 제외된다. */
        fun findCandidates(html: String, pageUrl: String): List<String> {
            val found = LinkedHashSet<String>()
            listOf("og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream").forEach { name ->
                metaContent(html, name)?.let { found += it }
            }
            SRC_ATTR.findAll(html).forEach { found += it.groupValues[1] }
            MEDIA_URL.findAll(html).forEach { found += it.value }
            return found.asSequence()
                .map { unescape(it.replace("\\/", "/").replace("\\u002F", "/").replace("\\u0026", "&")) }
                .filterNot { it.startsWith("blob:") || it.startsWith("data:") }
                .map { if (it.startsWith("//")) "https:$it" else it }
                .map { HlsParser.resolve(pageUrl, it) }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
                .toList()
        }

        private fun metaContent(html: String, property: String): String? {
            val p = Regex.escape(property)
            val patterns = listOf(
                Regex("<meta[^>]+(?:property|name)\\s*=\\s*[\"']$p[\"'][^>]*content\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
                Regex("<meta[^>]+content\\s*=\\s*[\"']([^\"']+)[\"'][^>]*(?:property|name)\\s*=\\s*[\"']$p[\"']", RegexOption.IGNORE_CASE),
            )
            return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }?.let(::unescape)?.trim()
        }

        private fun unescape(s: String): String =
            s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

        fun extensionFromUrl(url: String): String? =
            url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()
                ?.substringAfterLast('.', "")
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) }

        private fun fileTitle(url: String): String =
            url.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotBlank() }?.substringBeforeLast('.')
                ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
                ?.takeIf { it.isNotBlank() }
                ?: url.toHttpUrlOrNull()?.host
                ?: "video"

        private fun contentDispositionName(header: String?): String? {
            header ?: return null
            Regex("filename\\*\\s*=\\s*UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(header)?.let {
                return java.net.URLDecoder.decode(it.groupValues[1].trim(), Charsets.UTF_8)
            }
            return Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.trim()
        }
    }
}
