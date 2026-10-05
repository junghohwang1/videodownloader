package com.example.videodownloader.download

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class HlsVariant(val url: String, val bandwidth: Long, val resolution: String?)

data class HlsKey(val method: String, val uri: String?, val iv: ByteArray?) {
    override fun equals(other: Any?): Boolean =
        other is HlsKey && method == other.method && uri == other.uri &&
            iv.contentEquals(other.iv)

    override fun hashCode(): Int = 31 * (31 * method.hashCode() + (uri?.hashCode() ?: 0)) + (iv?.contentHashCode() ?: 0)
}

data class HlsSegment(
    val url: String,
    val sequence: Long,
    val durationSec: Double,
    val key: HlsKey?,
    /** 포함 범위 [first, last] 바이트. null 이면 파일 전체. */
    val byteRange: LongRange?,
)

data class HlsMediaPlaylist(
    val segments: List<HlsSegment>,
    val initSegment: HlsSegment?,
    val isEnded: Boolean,
) {
    val durationSec: Double get() = segments.sumOf { it.durationSec }
}

/** RFC 8216 중 다운로드에 필요한 부분만 해석하는 최소 파서. */
object HlsParser {

    fun isMaster(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    fun parseMaster(text: String, baseUrl: String): List<HlsVariant> {
        val variants = mutableListOf<HlsVariant>()
        var pending: Map<String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> pending = parseAttributes(line.substringAfter(':'))
                line.isEmpty() || line.startsWith("#") -> Unit
                pending != null -> {
                    val attrs = pending
                    variants += HlsVariant(
                        url = resolve(baseUrl, line),
                        bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                        resolution = attrs["RESOLUTION"],
                    )
                    pending = null
                }
            }
        }
        return variants
    }

    fun parseMedia(text: String, baseUrl: String): HlsMediaPlaylist {
        val segments = mutableListOf<HlsSegment>()
        var initSegment: HlsSegment? = null
        var sequence = 0L
        var duration = 0.0
        var key: HlsKey? = null
        var pendingRange: Pair<Long, Long?>? = null // (length, offset)
        val lastRangeEnd = mutableMapOf<String, Long>()
        var ended = false

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L

                line.startsWith("#EXTINF:") ->
                    duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0

                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    val method = attrs["METHOD"] ?: "NONE"
                    key = if (method == "NONE") null else HlsKey(
                        method = method,
                        uri = attrs["URI"]?.let { resolve(baseUrl, it) },
                        iv = attrs["IV"]?.let(::parseHexIv),
                    )
                }

                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    val uri = attrs["URI"]
                    if (uri != null) {
                        val url = resolve(baseUrl, uri)
                        val range = attrs["BYTERANGE"]?.let { parseByteRange(it) }?.let { (len, off) ->
                            val start = off ?: 0L
                            start until start + len
                        }
                        initSegment = HlsSegment(url, -1, 0.0, null, range)
                    }
                }

                line.startsWith("#EXT-X-BYTERANGE:") -> pendingRange = parseByteRange(line.substringAfter(':'))

                line.startsWith("#EXT-X-ENDLIST") -> ended = true

                line.isEmpty() || line.startsWith("#") -> Unit

                else -> {
                    val url = resolve(baseUrl, line)
                    val range = pendingRange?.let { (len, off) ->
                        val start = off ?: lastRangeEnd[url] ?: 0L
                        lastRangeEnd[url] = start + len
                        start until start + len
                    }
                    segments += HlsSegment(url, sequence, duration, key, range)
                    sequence++
                    duration = 0.0
                    pendingRange = null
                }
            }
        }
        return HlsMediaPlaylist(segments, initSegment, ended)
    }

    /** `KEY=VALUE,KEY="quoted, value"` 형태의 속성 목록. */
    fun parseAttributes(input: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = 0
        while (i < input.length) {
            val eq = input.indexOf('=', i)
            if (eq < 0) break
            val name = input.substring(i, eq).trim().trimStart(',').trim()
            var j = eq + 1
            val value: String
            if (j < input.length && input[j] == '"') {
                val close = input.indexOf('"', j + 1).let { if (it < 0) input.length else it }
                value = input.substring(j + 1, close)
                j = close + 1
            } else {
                val comma = input.indexOf(',', j).let { if (it < 0) input.length else it }
                value = input.substring(j, comma).trim()
                j = comma
            }
            if (name.isNotEmpty()) result[name] = value
            i = if (j < input.length && input[j] == ',') j + 1 else j
        }
        return result
    }

    fun resolve(base: String, reference: String): String =
        base.toHttpUrlOrNull()?.resolve(reference)?.toString() ?: reference

    private fun parseByteRange(value: String): Pair<Long, Long?>? {
        val parts = value.trim().split('@')
        val length = parts[0].toLongOrNull() ?: return null
        return length to parts.getOrNull(1)?.toLongOrNull()
    }

    private fun parseHexIv(value: String): ByteArray? {
        val hex = value.removePrefix("0x").removePrefix("0X")
        if (hex.isEmpty() || hex.length > 32) return null
        val padded = hex.padStart(32, '0')
        return ByteArray(16) { padded.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** IV 가 명시되지 않으면 미디어 시퀀스 번호를 16바이트 빅엔디언으로 쓴다. */
    fun sequenceIv(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        var v = sequence
        for (i in 15 downTo 8) {
            iv[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        return iv
    }
}
