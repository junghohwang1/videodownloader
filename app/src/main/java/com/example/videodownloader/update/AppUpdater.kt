package com.example.videodownloader.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.videodownloader.BuildConfig
import com.example.videodownloader.download.executeCancellable
import com.example.videodownloader.util.AppShare
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class UpdateInfo(val version: String, val notes: String, val apkUrl: String)

/**
 * 앱 안 업데이트. 공개 저장소의 최신 릴리스를 확인해 지금 버전보다 높으면 알리고,
 * APK 를 받아 시스템 설치 화면을 띄운다(같은 서명키라 덮어 설치된다).
 * 처음 한 번은 시스템이 「이 출처 허용」을 묻는다.
 */
class AppUpdater(private val context: Context, private val client: OkHttpClient) {

    private val _available = MutableStateFlow<UpdateInfo?>(null)

    /** 설치할 수 있는 새 버전. 사용자가 "나중에"를 누르면 null 로 돌아간다. */
    val available: StateFlow<UpdateInfo?> = _available.asStateFlow()

    private val _progress = MutableStateFlow<Float?>(null)

    /** APK 를 받는 중이면 0..1(크기를 모르면 -1), 아니면 null. */
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    private var checkedOnLaunch = false

    /** 앱을 켤 때 한 번 조용히 확인한다. 실패해도 알리지 않는다. */
    suspend fun checkOnLaunch() {
        if (checkedOnLaunch) return
        checkedOnLaunch = true
        runCatching { check() }
    }

    /** 최신 릴리스를 확인한다. 새 버전이 있으면 [available] 에 넣고 돌려준다. */
    suspend fun check(): UpdateInfo? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${AppShare.REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val json = client.executeCancellable(request) { response ->
            if (!response.isSuccessful) throw IOException("업데이트 서버 응답 오류 (HTTP ${response.code})")
            JSONObject(response.body.string())
        }
        val version = json.optString("tag_name").removePrefix("v").trim()
        if (version.isEmpty() || compareVersions(version, BuildConfig.VERSION_NAME) <= 0) return null
        val assets = json.optJSONArray("assets")
        val apkUrl = (0 until (assets?.length() ?: 0))
            .map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name") == AppShare.APK_NAME }
            ?.optString("browser_download_url")
            ?.takeIf { it.isNotBlank() }
            ?: AppShare.DOWNLOAD_URL
        return UpdateInfo(version, json.optString("body").trim(), apkUrl).also { _available.value = it }
    }

    fun dismiss() {
        _available.value = null
    }

    /** APK 를 받아 설치 화면을 연다. */
    suspend fun downloadAndInstall(info: UpdateInfo) {
        if (_progress.value != null) return
        _progress.value = 0f
        try {
            val file = File(context.cacheDir, "updates/${AppShare.APK_NAME}")
            file.parentFile?.mkdirs()
            val part = File(file.path + ".part")
            client.executeCancellable(Request.Builder().url(info.apkUrl).build()) { response ->
                if (!response.isSuccessful) throw IOException("설치 파일을 받을 수 없습니다 (HTTP ${response.code})")
                val total = response.body.contentLength()
                var done = 0L
                part.outputStream().use { out ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            _progress.value = if (total > 0) done.toFloat() / total else -1f
                        }
                    }
                }
                if (total > 0 && done < total) throw IOException("연결이 끊겨 설치 파일을 다 받지 못했습니다")
            }
            file.delete()
            if (!part.renameTo(file)) throw IOException("설치 파일을 저장할 수 없습니다")
            install(file)
        } finally {
            _progress.value = null
        }
    }

    private fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    companion object {
        /** "1.0.10" 과 "1.0.9" 처럼 점으로 나눈 숫자를 자리별로 비교한다. */
        fun compareVersions(a: String, b: String): Int {
            val x = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val diff = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
                if (diff != 0) return diff
            }
            return 0
        }
    }
}
