package com.example.videodownloader.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/** 파일 관리자 앱으로 폴더를 연다. 삼성 "내 파일"을 먼저 시도하고, 안 되면 기본 파일 앱(DocumentsUI)으로 연다. */
object FolderOpener {

    /** 열지 못하면 false(앱 전용 폴더처럼 다른 앱이 볼 수 없는 곳이거나 파일 앱이 없을 때). */
    fun open(context: Context, dir: File): Boolean {
        dir.mkdirs()
        val root = Environment.getExternalStorageDirectory().absolutePath
        val path = dir.absolutePath
        if (!path.startsWith(root) || path.contains("/Android/data/")) return false

        val samsung = Intent("samsung.myfiles.intent.action.LAUNCH_MY_FILES")
            .setPackage("com.sec.android.app.myfiles")
            .putExtra("samsung.myfiles.intent.extra.START_PATH", path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(context, samsung)) return true

        val relative = path.removePrefix(root).trimStart('/')
        val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$relative")
        val documents = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return tryStart(context, documents)
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
