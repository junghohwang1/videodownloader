package com.example.videodownloader.subtitle

/** 자막 한 줄. 시간은 초 단위. */
data class Cue(val start: Double, val end: Double, val text: String)

/** SRT 읽기·쓰기. */
object Srt {
    private val TIME = Regex("""(\d+):(\d+):(\d+)[,.](\d+)\s*-->\s*(\d+):(\d+):(\d+)[,.](\d+)""")

    fun parse(text: String): List<Cue> =
        text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).mapNotNull { block ->
            val lines = block.trim().lines()
            val timeIndex = lines.indexOfFirst { TIME.containsMatchIn(it) }
            if (timeIndex < 0) return@mapNotNull null
            val m = TIME.find(lines[timeIndex])!!.groupValues
            fun seconds(h: String, mi: String, s: String, ms: String) =
                h.toInt() * 3600 + mi.toInt() * 60 + s.toInt() + ms.padEnd(3, '0').take(3).toInt() / 1000.0
            val body = lines.drop(timeIndex + 1).joinToString("\n").trim()
            if (body.isEmpty()) null else Cue(seconds(m[1], m[2], m[3], m[4]), seconds(m[5], m[6], m[7], m[8]), body)
        }

    /**
     * 자막이 다음 말이 나올 때까지(무음 건너뛰기로 지운 긴 무음 동안) 계속 떠 있지 않도록,
     * 글자 수에 맞는 길이(최소 2초, 최대 12초)를 넘으면 자르고, 다음 자막과 겹치지 않게 한다.
     */
    fun tidy(cues: List<Cue>): List<Cue> = cues.mapIndexed { i, cue ->
        val readable = (2.0 + cue.text.length * 0.1).coerceAtMost(12.0)
        val next = cues.getOrNull(i + 1)?.start ?: Double.MAX_VALUE
        cue.copy(end = minOf(cue.end, cue.start + readable, maxOf(next, cue.start + 0.5)))
    }

    fun write(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { i, cue ->
            append(i + 1).append('\n')
            append(timestamp(cue.start)).append(" --> ").append(timestamp(cue.end)).append('\n')
            append(cue.text).append("\n\n")
        }
    }

    private fun timestamp(seconds: Double): String {
        val ms = (seconds * 1000).toLong().coerceAtLeast(0)
        return "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }
}
