package com.example.videodownloader.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** MPEG-TS 를 MP4 로 옮겨 담는다(재인코딩 없음). 실패하면 false 를 돌려주고 호출자는 TS 를 그대로 둔다. */
object Remuxer {
    private const val TAG = "Remuxer"

    @OptIn(UnstableApi::class)
    suspend fun remuxToMp4(context: Context, input: File, output: File): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        Log.w(TAG, "MP4 변환 실패, TS 로 저장합니다", exportException)
                        output.delete()
                        if (cont.isActive) cont.resume(false)
                    }
                })
                .build()
            cont.invokeOnCancellation {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    transformer.cancel()
                    output.delete()
                }
            }
            try {
                transformer.start(MediaItem.fromUri(Uri.fromFile(input)), output.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "MP4 변환을 시작할 수 없습니다", e)
                if (cont.isActive) cont.resume(false)
            }
        }
    }
}
