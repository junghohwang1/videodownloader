package com.example.videodownloader.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

/**
 * 저장 위치 관리.
 * - 일반 다운로드: 내장 저장공간/Download/VideoDownloader(Android 11+). "내 파일"·갤러리에서 바로 보인다.
 *   앱이 만든 파일은 저장소 권한 없이 쓰고 지울 수 있다. Download 폴더는 영상 외 파일(.part, .srt)도 둘 수 있다.
 *   Android 10 이나 공용 폴더를 쓸 수 없는 기기는 앱 전용 폴더(Android/data/<패키지>/files/Movies)에 둔다.
 * - 비공개 폴더: 앱 내부 저장소(files/private). 다른 앱·파일 관리자에서 보이지 않는다.
 * - "갤러리로 내보내기"는 MediaStore 를 통해 Movies/VideoDownloader 에 옮긴다.
 */
class Storage(private val context: Context) {

    val publicDir: File
        get() = sharedDownloadDir() ?: appDownloadDir()

    private fun sharedDownloadDir(): File? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), EXPORT_FOLDER)
        return dir.takeIf { (it.isDirectory || it.mkdirs()) && it.canWrite() }
    }

    private fun appDownloadDir(): File =
        (context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "videos"))
            .also { it.mkdirs() }

    val privateDir: File
        get() = File(context.filesDir, "private").also { it.mkdirs() }

    fun dirFor(isPrivate: Boolean): File = if (isPrivate) privateDir else publicDir

    /** 조각 파일 등 다운로드 중간 산출물을 두는 폴더(완료·삭제 시 지운다). */
    fun tempDir(downloadId: Long): File = File(context.noBackupFilesDir, "work/$downloadId")

    /** 이름이 겹치지 않는 파일 경로를 고르고, 빈 .part 파일을 만들어 이름을 선점한다. */
    @Synchronized
    fun reserveFile(dir: File, desiredName: String): File {
        val file = uniqueFile(dir, desiredName)
        File(file.path + PART).createNewFile()
        return file
    }

    @Synchronized
    fun uniqueFile(dir: File, desiredName: String): File {
        dir.mkdirs()
        val clean = sanitizeFileName(desiredName)
        val base = clean.substringBeforeLast('.', clean)
        val ext = clean.substringAfterLast('.', "").let { if (it.isEmpty() || it == clean) "" else ".$it" }
        var i = 0
        while (true) {
            val name = if (i == 0) "$base$ext" else "$base ($i)$ext"
            val candidate = File(dir, name)
            if (!candidate.exists() && !File(candidate.path + PART).exists()) return candidate
            i++
        }
    }

    /** 다른 볼륨(외부↔내부)으로의 이동은 rename 이 실패하므로 복사 후 삭제한다. */
    fun moveFile(src: File, dstDir: File): File {
        val dst = uniqueFile(dstDir, src.name)
        if (!src.renameTo(dst)) {
            src.copyTo(dst)
            src.delete()
        }
        return dst
    }

    /** 갤러리에 복사하고, 실제로 저장된 파일 이름(겹치면 시스템이 바꾼다)을 돌려준다. */
    fun exportToGallery(file: File, mimeType: String?): String {
        val mime = mimeType ?: guessMime(file.name)
        val (collection, relativePath) = when {
            mime.startsWith("video/") ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
            mime.startsWith("audio/") ->
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
            mime.startsWith("image/") ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            else ->
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_DOWNLOADS
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativePath/$EXPORT_FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("미디어 저장소에 항목을 만들 수 없습니다")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("저장소를 열 수 없습니다")
            out.use { output -> file.inputStream().use { it.copyTo(output) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return displayName(uri) ?: file.name
    }

    /**
     * 자막을 갤러리로 내보낸 영상 옆(Movies/VideoDownloader)에 같은 이름으로 둔다.
     * 동영상 플레이어들이 같은 이름의 .srt 를 자동으로 붙여 준다. 실패해도 영상 내보내기는 그대로 둔다.
     */
    fun exportSubtitle(subtitle: File, exportedVideoName: String) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, exportedVideoName.substringBeforeLast('.') + ".srt")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$EXPORT_FOLDER")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: return
            resolver.openOutputStream(uri)?.use { out -> subtitle.inputStream().use { it.copyTo(out) } }
        }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    companion object {
        const val PART = ".part"

        /** 영상과 같은 이름의 자막 파일(영상.srt). 영상을 옮기거나 지울 때 함께 다룬다. */
        fun subtitleFor(video: File): File = File(video.parentFile, video.nameWithoutExtension + ".srt")

        const val EXPORT_FOLDER = "VideoDownloader"
        private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
        private val SPACES = Regex("\\s+")

        fun sanitizeFileName(name: String): String {
            val cleaned = name.replace(ILLEGAL, "_").replace(SPACES, " ").trim().trim('.')
            if (cleaned.isEmpty()) return "video"
            if (cleaned.length <= 120) return cleaned
            val ext = cleaned.substringAfterLast('.', "")
            return if (ext.isNotEmpty() && ext.length <= 5) {
                cleaned.substringBeforeLast('.').take(110).trimEnd() + "." + ext
            } else {
                cleaned.take(120)
            }
        }

        fun guessMime(fileName: String): String =
            MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
                ?: "video/mp4"

        fun isPlayable(mimeType: String?, fileName: String): Boolean {
            val mime = mimeType ?: guessMime(fileName)
            return mime.startsWith("video/") || mime.startsWith("audio/") ||
                fileName.endsWith(".ts", ignoreCase = true)
        }
    }
}
