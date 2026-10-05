package com.example.videodownloader.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * 영상 전용 파일과 음성 전용 파일을 재인코딩 없이 MP4 하나로 합친다(H.264 + AAC).
 * 두 트랙을 시간순으로 번갈아 써서 큰 파일도 메모리에 쌓이지 않게 한다.
 */
object Mp4Muxer {

    fun mux(video: File, audio: File, output: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(video.path)
            audioExtractor.setDataSource(audio.path)
            val videoTrack = selectTrack(videoExtractor, "video/") ?: throw IOException("영상 트랙이 없습니다")
            val audioTrack = selectTrack(audioExtractor, "audio/") ?: throw IOException("음성 트랙이 없습니다")
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)

            muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION))
            }
            val outVideo = muxer.addTrack(videoFormat)
            val outAudio = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocate(
                maxOf(maxInputSize(videoFormat), maxInputSize(audioFormat), 4 * 1024 * 1024),
            )
            val info = MediaCodec.BufferInfo()
            var videoDone = false
            var audioDone = false
            while (!videoDone || !audioDone) {
                // 아직 남은 트랙 중 타임스탬프가 앞선 쪽을 먼저 쓴다.
                val useVideo = when {
                    videoDone -> false
                    audioDone -> true
                    else -> videoExtractor.sampleTime <= audioExtractor.sampleTime
                }
                val extractor = if (useVideo) videoExtractor else audioExtractor
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    if (useVideo) videoDone = true else audioDone = true
                    continue
                }
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                muxer.writeSampleData(if (useVideo) outVideo else outAudio, buffer, info)
                extractor.advance()
            }
            muxer.stop()
        } catch (e: Exception) {
            output.delete()
            throw if (e is IOException) e else IOException("영상과 음성을 합치지 못했습니다: ${e.message}", e)
        } finally {
            runCatching { muxer?.release() }
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) {
                extractor.selectTrack(i)
                return i
            }
        }
        return null
    }

    private fun maxInputSize(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
}
