package com.example.videodownloader.download

import com.example.videodownloader.data.DownloadEntity
import com.example.videodownloader.util.Storage
import com.example.videodownloader.util.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS(m3u8) 다운로드.
 * 1) 마스터 플레이리스트면 가장 높은 화질을 고른다.
 * 2) 세그먼트를 임시 폴더에 병렬로 받는다(이미 받은 세그먼트는 건너뛰어 이어받기).
 * 3) AES-128 암호화 세그먼트는 복호화한다. SAMPLE-AES 등 DRM 은 지원하지 않는다.
 * 4) 순서대로 이어 붙인 뒤, TS 는 ffmpeg 로 MP4 리먹싱을 시도한다(실패하면 TS 로 저장).
 */
class HlsDownloader(
    private val client: OkHttpClient,
    private val storage: Storage,
    private val ffmpeg: Ffmpeg,
) {

    suspend fun download(task: DownloadEntity, listener: ProgressListener): DownloadResult {
        var playlistUrl = task.url
        var text = fetchText(playlistUrl, task)
        if (HlsParser.isMaster(text)) {
            val best = HlsParser.parseMaster(text, playlistUrl).maxByOrNull { it.bandwidth }
                ?: throw IOException("재생 가능한 화질 정보를 찾지 못했습니다")
            playlistUrl = best.url
            text = fetchText(playlistUrl, task)
        }

        val playlist = HlsParser.parseMedia(text, playlistUrl)
        if (!playlist.isEnded) throw UnsupportedMediaException("라이브 방송은 다운로드할 수 없습니다")
        if (playlist.segments.isEmpty()) throw IOException("세그먼트가 없는 플레이리스트입니다")
        playlist.segments.firstOrNull { it.key != null && it.key.method != "AES-128" }?.let {
            throw UnsupportedMediaException("DRM 보호 영상(${it.key?.method})은 다운로드할 수 없습니다")
        }

        val tempDir = storage.tempDir(task.id).also { it.mkdirs() }
        val segments = playlist.segments
        val total = segments.size
        val files = segments.indices.map { File(tempDir, String.format("%06d.seg", it)) }
        val done = AtomicInteger(files.count { it.exists() })
        val bytes = AtomicLong(files.filter { it.exists() }.sumOf { it.length() })
        val keyCache = ConcurrentHashMap<String, ByteArray>()
        listener.onProgress(bytes.get(), -1, done.get(), total)

        val initFile = File(tempDir, "init.seg")
        playlist.initSegment?.let { init ->
            if (!initFile.exists()) writeAtomically(initFile, fetchBytes(init.url, init.byteRange, task))
        }

        val semaphore = Semaphore(PARALLELISM)
        coroutineScope {
            segments.forEachIndexed { index, segment ->
                val file = files[index]
                if (file.exists()) return@forEachIndexed
                launch {
                    semaphore.withPermit {
                        val raw = fetchBytes(segment.url, segment.byteRange, task)
                        val data = segment.key?.let { key -> decrypt(raw, key, segment.sequence, task, keyCache) } ?: raw
                        writeAtomically(file, data)
                        val d = done.incrementAndGet()
                        val b = bytes.addAndGet(data.size.toLong())
                        listener.onProgress(b, -1, d, total)
                    }
                }
            }
        }

        val isFragmentedMp4 = playlist.initSegment != null
        val joined = File(tempDir, if (isFragmentedMp4) "joined.mp4" else "joined.ts")
        joined.outputStream().buffered().use { out ->
            if (initFile.exists()) initFile.inputStream().use { it.copyTo(out) }
            files.forEach { f -> f.inputStream().use { it.copyTo(out) } }
        }

        val placeholder = File(task.filePath)
        val dir = placeholder.parentFile ?: storage.downloadDir
        val baseName = placeholder.name.substringBeforeLast('.', placeholder.name)
        File(task.filePath + Storage.PART).delete()

        val result = if (isFragmentedMp4) {
            DownloadResult(moveInto(joined, storage.uniqueFile(dir, "$baseName.mp4")), "video/mp4")
        } else {
            prepareFfmpeg(listener)
            listener.onPhase("MP4 로 변환 중…")
            val mp4 = storage.uniqueFile(dir, "$baseName.mp4")
            if (ffmpeg.isAvailable && ffmpeg.remuxToMp4(joined, mp4)) {
                DownloadResult(mp4, "video/mp4")
            } else {
                DownloadResult(moveInto(joined, storage.uniqueFile(dir, "$baseName.ts")), "video/mp2t")
            }
        }
        listener.onPhase(null)
        tempDir.deleteRecursively()
        return result
    }

    /** ffmpeg 가 없으면 설치를 시도한다. 실패해도 TS 로 저장하면 되므로 오류를 내지 않는다. */
    private suspend fun prepareFfmpeg(listener: ProgressListener) {
        if (ffmpeg.isAvailable) return
        try {
            ffmpeg.ensure { downloaded, totalBytes ->
                listener.onPhase("ffmpeg 설치 중… ${formatBytes(downloaded)} / ${formatBytes(totalBytes)}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun moveInto(src: File, dst: File): File {
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
        return dst
    }

    private suspend fun fetchText(url: String, task: DownloadEntity): String =
        String(fetchBytes(url, null, task), Charsets.UTF_8)

    private suspend fun fetchBytes(url: String, range: LongRange?, task: DownloadEntity): ByteArray {
        var lastError: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .applyMediaHeaders(url, task.userAgent, task.referer)
                    .apply { range?.let { header("Range", "bytes=${it.first}-${it.last}") } }
                    .build()
                return client.executeCancellable(request) { response ->
                    if (!response.isSuccessful) throw IOException("세그먼트 응답 오류 (HTTP ${response.code})")
                    response.body.bytes()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastError = e
                delay(1_000L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("세그먼트를 받을 수 없습니다")
    }

    private suspend fun decrypt(
        data: ByteArray,
        key: HlsKey,
        sequence: Long,
        task: DownloadEntity,
        cache: ConcurrentHashMap<String, ByteArray>,
    ): ByteArray {
        val keyUri = key.uri ?: throw IOException("암호화 키 주소가 없습니다")
        val keyBytes = cache[keyUri] ?: fetchBytes(keyUri, null, task).also { cache[keyUri] = it }
        if (keyBytes.size != 16) throw UnsupportedMediaException("지원하지 않는 암호화 키 형식입니다")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(key.iv ?: HlsParser.sequenceIv(sequence)),
        )
        return cipher.doFinal(data)
    }

    private fun writeAtomically(file: File, data: ByteArray) {
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(file)) throw IOException("임시 파일을 저장할 수 없습니다")
    }

    private companion object {
        const val PARALLELISM = 4
        const val MAX_ATTEMPTS = 3
    }
}
