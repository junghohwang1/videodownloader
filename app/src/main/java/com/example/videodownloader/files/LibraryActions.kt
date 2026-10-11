package com.example.videodownloader.files

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.videodownloader.data.db.DownloadDao
import com.example.videodownloader.data.db.DownloadEntity
import com.example.videodownloader.download.DownloadManager
import com.example.videodownloader.player.PlayerActivity
import com.example.videodownloader.util.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** 완료된 파일에 대한 공통 동작(파일 탭·비공개 탭이 함께 쓴다). */
class LibraryActions(
    private val dao: DownloadDao,
    private val storage: Storage,
    private val downloadManager: DownloadManager,
) {
    suspend fun rename(item: DownloadEntity, newBaseName: String) = withContext(Dispatchers.IO) {
        val src = File(item.filePath)
        val ext = src.name.substringAfterLast('.', "")
        val desired = if (ext.isEmpty()) newBaseName else "$newBaseName.$ext"
        val dst = storage.uniqueFile(src.parentFile ?: storage.dirFor(item.isPrivate), desired)
        if (!src.renameTo(dst)) throw IOException("이름을 바꿀 수 없습니다")
        moveSubtitle(src, dst)
        dao.updateLocation(item.id, dst.path, dst.name, item.isPrivate)
    }

    suspend fun setPrivate(item: DownloadEntity, isPrivate: Boolean) = withContext(Dispatchers.IO) {
        val src = File(item.filePath)
        if (!src.exists()) throw IOException("파일이 없습니다")
        val dst = storage.moveFile(src, storage.dirFor(isPrivate))
        moveSubtitle(src, dst)
        dao.updateLocation(item.id, dst.path, dst.name, isPrivate)
    }

    /** 갤러리로 복사한 뒤 앱 쪽 원본과 목록 항목을 지운다(복사가 실패하면 아무것도 지우지 않는다). */
    suspend fun exportToGallery(item: DownloadEntity) {
        withContext(Dispatchers.IO) {
            val video = File(item.filePath)
            val name = storage.exportToGallery(video, item.mimeType)
            Storage.subtitleFor(video).takeIf { it.exists() }?.let { storage.exportSubtitle(it, name) }
        }
        downloadManager.delete(item.id, deleteFile = true)
    }

    /** 영상 이름이 바뀌거나 옮겨지면 자막 파일도 같은 이름으로 따라간다. */
    private fun moveSubtitle(oldVideo: File, newVideo: File) {
        val src = Storage.subtitleFor(oldVideo)
        if (!src.exists()) return
        val dst = Storage.subtitleFor(newVideo)
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
    }

    suspend fun delete(item: DownloadEntity) = downloadManager.delete(item.id, deleteFile = true)

    fun open(context: Context, item: DownloadEntity) {
        if (Storage.isPlayable(item.mimeType, item.fileName)) {
            context.startActivity(PlayerActivity.intent(context, item.filePath, item.fileName))
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(item.filePath))
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, item.mimeType ?: Storage.guessMime(item.fileName))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "열기"))
    }

    fun share(context: Context, item: DownloadEntity) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(item.filePath))
        val intent = Intent(Intent.ACTION_SEND)
            .setType(item.mimeType ?: Storage.guessMime(item.fileName))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "공유"))
    }
}
