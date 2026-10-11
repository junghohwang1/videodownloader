package com.example.videodownloader.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class LinearResamplerTest {
    @Test
    fun resamples48kTo16kAcrossBuffers() {
        val resampler = LinearResampler()
        // 0,1,2,… 가 이어지는 신호를 버퍼 두 개로 나눠 넣어도 3칸마다 하나씩 이어져야 한다.
        val first = FloatArray(10) { it.toFloat() }
        val second = FloatArray(10) { (it + 10).toFloat() }
        val out = resampler.process(first, 48_000).toList() + resampler.process(second, 48_000).toList()
        assertEquals(listOf(0f, 3f, 6f, 9f, 12f, 15f, 18f), out)
    }

    @Test
    fun upsamples8kTo16k() {
        val out = LinearResampler().process(floatArrayOf(0f, 2f, 4f), 8_000).toList()
        assertEquals(listOf(0f, 1f, 2f, 3f), out)
    }
}
