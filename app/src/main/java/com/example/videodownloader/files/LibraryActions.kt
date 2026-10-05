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
        dao.updateLocation(item.id, dst.path, dst.name, item.isPrivate)
    }

    suspend fun setPrivate(item: DownloadEntity, isPrivate: Boolean) = withContext(Dispatchers.IO) {
        val src = File(item.filePath)
        if (!src.exists()) throw IOException("파일이 없습니다")
        val dst = storage.moveFile(src, storage.dirFor(isPrivate))
        dao.updateLocation(item.id, dst.path, dst.name, isPrivate)
    }

    suspend fun exportToGallery(item: DownloadEntity) = withContext(Dispatchers.IO) {
        storage.exportToGallery(File(item.filePath), item.mimeType)
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
