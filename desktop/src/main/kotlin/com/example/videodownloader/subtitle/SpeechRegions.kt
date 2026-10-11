package com.example.videodownloader.subtitle

/**
 * 말소리 구간만 이어 붙인 오디오(압축 오디오)와 원래 오디오 사이의 시간 대응표.
 * 음성 인식은 압축 오디오로 하고(무음·음악 구간을 건너뛰어 빨라진다), 자막 시간은 이 표로 원래 시간으로 되돌린다.
 * 자막 시작이 구간 사이(이어 붙이며 넣은 짧은 무음)에 걸리면 다음 말소리 시작으로, 끝이 걸리면 앞 말소리 끝으로 맞춘다.
 */
class SpeechRegions private constructor(private val pieces: List<Piece>) {

    /** 압축 오디오의 [condStart, condEnd) 가 원래 오디오의 [origStart, origStart + 길이) 에 해당한다. */
    data class Piece(val condStart: Double, val condEnd: Double, val origStart: Double) {
        val origEnd: Double get() = origStart + (condEnd - condStart)
    }

    val isEmpty: Boolean get() = pieces.isEmpty()

    fun mapStart(t: Double): Double {
        pieces.forEach { p ->
            if (t < p.condStart) return p.origStart // 앞 구간과의 사이 → 이 구간 시작
            if (t < p.condEnd) return p.origStart + (t - p.condStart)
        }
        return pieces.lastOrNull()?.origEnd ?: t
    }

    fun mapEnd(t: Double): Double {
        var previous: Piece? = null
        pieces.forEach { p ->
            if (t <= p.condStart) return previous?.origEnd ?: p.origStart // 사이 → 앞 구간 끝
            if (t <= p.condEnd) return p.origStart + (t - p.condStart)
            previous = p
        }
        return pieces.lastOrNull()?.origEnd ?: t
    }

    /** 원래 오디오에서 [regions] 구간만 골라, 사이에 [GAP] 초 무음을 넣어 이어 붙인다. */
    fun condense(samples: FloatArray, sampleRate: Int): FloatArray {
        val gap = (GAP * sampleRate).toInt()
        val total = pieces.sumOf { ((it.condEnd - it.condStart) * sampleRate).toInt() } + gap * pieces.size
        val out = FloatArray(total)
        var pos = 0
        pieces.forEach { p ->
            val from = (p.origStart * sampleRate).toInt().coerceIn(0, samples.size)
            val count = ((p.condEnd - p.condStart) * sampleRate).toInt().coerceAtMost(samples.size - from)
            System.arraycopy(samples, from, out, pos, count)
            pos += count + gap
        }
        return out
    }

    companion object {
        /** 이어 붙일 때 구간 사이에 넣는 무음(초). 문장이 서로 붙어 인식되지 않게 한다. */
        const val GAP = 0.4

        /**
         * VAD 가 찾은 말소리 구간([start, end] 초 목록)을 정리해 대응표를 만든다.
         * [mergeGap] 초보다 가까운 구간은 하나로 합친다(짧은 숨 고르기마다 끊기지 않게).
         */
        fun build(regions: List<Pair<Double, Double>>, mergeGap: Double = 1.0): SpeechRegions {
            val merged = mutableListOf<Pair<Double, Double>>()
            regions.filter { it.second > it.first }.sortedBy { it.first }.forEach { r ->
                val last = merged.lastOrNull()
                if (last != null && r.first - last.second < mergeGap) {
                    merged[merged.lastIndex] = last.first to maxOf(last.second, r.second)
                } else {
                    merged += r
                }
            }
            var cursor = 0.0
            val pieces = merged.map { (start, end) ->
                Piece(cursor, cursor + (end - start), start).also { cursor = it.condEnd + GAP }
            }
            return SpeechRegions(pieces)
        }
    }
}
