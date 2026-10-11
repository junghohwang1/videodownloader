package com.example.videodownloader.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechRegionsTest {
    // 말소리: 0.5~19초, 79.6~98초(사이 60초 무음). 이어 붙이면 [0, 18.5) + 0.4초 + [18.9, 37.3)
    private val regions = SpeechRegions.build(listOf(0.5 to 14.0, 14.3 to 19.0, 79.6 to 98.0))

    @Test
    fun mergesCloseRegionsAndMapsBack() {
        assertEquals(0.5, regions.mapStart(0.0), 1e-6)
        assertEquals(10.5, regions.mapStart(10.0), 1e-6)
        assertEquals(19.0, regions.mapEnd(18.5), 1e-6)
        // 두 번째 구간 안의 시간
        assertEquals(79.6 + 1.1, regions.mapStart(18.9 + 1.1), 1e-6)
    }

    @Test
    fun snapsTimesInsideTheInsertedGap() {
        // 사이 무음(18.5~18.9)에 걸린 시작은 다음 말소리 시작으로, 끝은 앞 말소리 끝으로
        assertEquals(79.6, regions.mapStart(18.7), 1e-6)
        assertEquals(19.0, regions.mapEnd(18.7), 1e-6)
    }

    @Test
    fun condensesSamples() {
        val rate = 10
        val samples = FloatArray(1000) { it.toFloat() }
        val out = regions.condense(samples, rate)
        // 18.5초 + 0.4초 + 18.4초 + 0.4초(끝) = 37.7초 → 377 샘플
        assertEquals(377, out.size)
        assertEquals(5f, out[0]) // 0.5초 → 5번째 샘플
        assertEquals(796f, out[189]) // 두 번째 구간 시작(18.9초 → 189번째)
    }

    @Test
    fun tidyCutsSubtitlesThatSpanLongSilence() {
        val cues = Srt.tidy(listOf(Cue(16.6, 80.0, "And that's pretty much all there is to say."), Cue(80.0, 84.0, "Next")))
        assertEquals(16.6 + 2.0 + 4.3, cues[0].end, 1e-6)
    }
}
