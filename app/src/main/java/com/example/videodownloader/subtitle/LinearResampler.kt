package com.example.videodownloader.subtitle

import kotlin.math.floor

/**
 * 버퍼가 나뉘어 들어와도 이어지도록 위치를 기억하는 선형 보간 리샘플러(기본 → 16kHz).
 * 말소리 인식에는 이 정도 품질로 충분하다.
 */
internal class LinearResampler(private val outputRate: Int = 16_000) {
    private var position = 0.0
    private var previous = 0f

    fun process(input: FloatArray, inputRate: Int): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        val step = inputRate.toDouble() / outputRate
        val n = input.size
        val out = FloatArray(((n - position) / step).toInt() + 2)
        var count = 0
        while (true) {
            val i0 = floor(position).toInt()
            if (i0 + 1 >= n) break
            val a = if (i0 < 0) previous else input[i0]
            val b = input[i0 + 1]
            out[count++] = a + (b - a) * (position - i0).toFloat()
            position += step
        }
        position -= n
        previous = input[n - 1]
        return out.copyOf(count)
    }
}
