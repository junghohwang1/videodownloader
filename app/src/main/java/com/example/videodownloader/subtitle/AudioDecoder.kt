package com.example.videodownloader.subtitle

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * 영상·음성 파일의 소리를 음성 인식용 16kHz 모노 float 로 풀어 [chunkSeconds] 초씩 넘긴다.
 * 긴 영상도 메모리를 적게 쓰도록 조각으로 나눠 처리한다.
 */
object AudioDecoder {
    const val SAMPLE_RATE = 16_000

    /** 소리 길이(초). 알 수 없으면 0. */
    fun durationSeconds(file: File): Double {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            val track = audioTrack(extractor) ?: return 0.0
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1e6 else 0.0
        } catch (_: Exception) {
            0.0
        } finally {
            extractor.release()
        }
    }

    suspend fun decode(
        file: File,
        chunkSeconds: Int,
        onChunk: suspend (samples: FloatArray, startSeconds: Double) -> Unit,
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.path)
        val track = audioTrack(extractor) ?: run {
            extractor.release()
            throw IOException("소리가 없는 파일입니다")
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        val chunk = FloatArray(chunkSeconds * SAMPLE_RATE)
        var filled = 0
        var chunkStart = 0.0
        suspend fun emit(samples: FloatArray) {
            var offset = 0
            while (offset < samples.size) {
                val count = minOf(samples.size - offset, chunk.size - filled)
                System.arraycopy(samples, offset, chunk, filled, count)
                filled += count
                offset += count
                if (filled == chunk.size) {
                    onChunk(chunk.copyOf(), chunkStart)
                    chunkStart += chunkSeconds
                    filled = 0
                }
            }
        }

        var sampleRate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
        var channels = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
        var floatPcm = false
        val resampler = LinearResampler()

        try {
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        sampleRate = out.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = out.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        floatPcm = out.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) ==
                            AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val mono = toMono(buffer, channels, floatPcm)
                        codec.releaseOutputBuffer(outIndex, false)
                        emit(resampler.process(mono, sampleRate))
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            // 1초보다 짧은 끝부분은 인식할 말이 거의 없어 버린다.
            if (filled >= SAMPLE_RATE) onChunk(chunk.copyOf(filled), chunkStart)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
    }

    private fun audioTrack(extractor: MediaExtractor): Int? =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }

    private fun toMono(buffer: java.nio.ByteBuffer, channels: Int, floatPcm: Boolean): FloatArray {
        val ch = channels.coerceAtLeast(1)
        return if (floatPcm) {
            val floats = buffer.asFloatBuffer()
            FloatArray(floats.remaining() / ch) { i ->
                var sum = 0f
                for (c in 0 until ch) sum += floats.get(i * ch + c)
                sum / ch
            }
        } else {
            val shorts = buffer.asShortBuffer()
            FloatArray(shorts.remaining() / ch) { i ->
                var sum = 0f
                for (c in 0 until ch) sum += shorts.get(i * ch + c)
                sum / ch / 32768f
            }
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private const val TIMEOUT_US = 10_000L
}
