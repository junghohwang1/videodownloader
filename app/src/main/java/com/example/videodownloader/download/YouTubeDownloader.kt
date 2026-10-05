package com.example.videodownloader.download

import com.example.videodownloader.data.db.DownloadEntity
import com.example.videodownloader.util.Storage
import com.example.videodownloader.youtube.YouTubeSupport
import com.example.videodownloader.youtube.YtSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/**
 * 유튜브 다운로드. 시작할 때마다 스트림 주소를 새로 받아(주소는 몇 시간 뒤 만료) 조각 단위로 내려받고,
 * 영상·음성이 나뉜 형식이면 MP4 로 합친다. 받던 조각은 임시 폴더에 남아 다음에 이어받는다.
 */
class YouTubeDownloader(
    private val client: OkHttpClient,
    private val storage: Storage,
) {
    suspend fun download(task: DownloadEntity, listener: ProgressListener): DownloadResult {
        val spec = YtSpec.parse(task.formatSpec ?: throw IOException("형식 정보가 없습니다"))
        val streams = YouTubeSupport.resolveStreams(task.url, spec)
        val tempDir = storage.tempDir(task.id).also { it.mkdirs() }

        val parts = listOfNotNull(
            streams.video?.let { it to File(tempDir, "video.part") },
            streams.audio?.let { it to File(tempDir, "audio.part") },
        )
        val total = parts.map { it.first.contentLength }.let { sizes -> if (sizes.all { it > 0 }) sizes.sum() else -1L }

        var finishedBytes = 0L
        for ((ref, file) in parts) {
            val size = client.downloadRanged(
                url = ref.url,
                headers = YouTubeSupport.streamHeaders(ref.url),
                part = file,
                knownTotal = ref.contentLength,
            ) { downloaded, _ ->
                listener.onProgress(finishedBytes + downloaded, total, 0, 0)
            }
            finishedBytes += size
        }

        val placeholder = File(task.filePath)
        val dir = placeholder.parentFile ?: storage.dirFor(task.isPrivate)
        val baseName = placeholder.name.substringBeforeLast('.', placeholder.name)
        File(task.filePath + Storage.PART).delete()

        val result = withContext(Dispatchers.IO) {
            val video = parts.firstOrNull { it.second.name == "video.part" }?.second
            val audio = parts.firstOrNull { it.second.name == "audio.part" }?.second
            when {
                video != null && audio != null -> {
                    val out = storage.uniqueFile(dir, "$baseName.mp4")
                    Mp4Muxer.mux(video, audio, out)
                    DownloadResult(out, "video/mp4")
                }
                audio != null -> DownloadResult(moveInto(audio, storage.uniqueFile(dir, "$baseName.m4a")), "audio/mp4")
                video != null -> DownloadResult(moveInto(video, storage.uniqueFile(dir, "$baseName.mp4")), "video/mp4")
                else -> throw IOException("받은 스트림이 없습니다")
            }
        }
        tempDir.deleteRecursively()
        return result
    }

    private fun moveInto(src: File, dst: File): File {
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
        return dst
    }
}
