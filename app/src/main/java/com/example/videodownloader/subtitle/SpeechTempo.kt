package com.example.videodownloader.subtitle

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 「빠른 자막 만들기」: 음성 인식 전에 소리를 [speed] 배로 빠르게 한다(음 높이는 그대로, Media3 Sonic).
 * 인식할 분량이 그만큼 줄어 빨라지는 대신, 말이 빠른 영상은 조금 덜 정확해질 수 있다.
 * 자막 시간은 호출하는 쪽에서 [speed] 를 곱해 원래 속도로 되돌린다.
 */
object SpeechTempo {

    @OptIn(UnstableApi::class)
    fun speedUp(samples: FloatArray, speed: Float, sampleRate: Int = AudioDecoder.SAMPLE_RATE): FloatArray {
        if (speed == 1f || samples.isEmpty()) return samples
        val sonic = SonicAudioProcessor()
        sonic.setSpeed(speed)
        sonic.configure(AudioProcessor.AudioFormat(sampleRate, 1, C.ENCODING_PCM_16BIT))
        sonic.flush(AudioProcessor.StreamMetadata.DEFAULT)

        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort((it.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()) }
        input.flip()

        val out = ByteArrayOutputStream(samples.size * 2)
        fun drain() {
            val buffer = sonic.output
            while (buffer.hasRemaining()) out.write(buffer.get().toInt())
        }
        while (input.hasRemaining()) {
            sonic.queueInput(input)
            drain()
        }
        sonic.queueEndOfStream()
        while (!sonic.isEnded) drain()

        val bytes = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.nativeOrder())
        return FloatArray(bytes.remaining() / 2) { bytes.getShort().toFloat() / Short.MAX_VALUE }
    }
}
