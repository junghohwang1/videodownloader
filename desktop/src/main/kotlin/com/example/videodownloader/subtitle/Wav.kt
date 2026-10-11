package com.example.videodownloader.subtitle

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 음성 인식용 16비트 모노 PCM WAV 읽기·쓰기(ffmpeg 로 뽑은 파일). */
object Wav {

    fun read(file: File): FloatArray {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") throw IOException("WAV 파일이 아닙니다")
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            if (id == "data") {
                val count = minOf(size, bytes.size - pos - 8) / 2
                return FloatArray(count) { i -> buf.getShort(pos + 8 + i * 2) / 32768f }
            }
            pos += 8 + size + (size and 1)
        }
        throw IOException("WAV 에 소리 데이터가 없습니다")
    }

    fun write(file: File, samples: FloatArray, sampleRate: Int = 16_000) {
        val dataSize = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
            .putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(dataSize)
        samples.forEach { buf.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        file.writeBytes(buf.array())
    }
}
