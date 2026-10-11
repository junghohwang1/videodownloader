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
