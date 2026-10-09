package com.example.videodownloader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.videodownloader.download.DEFAULT_USER_AGENT
import com.example.videodownloader.util.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

private val thumbnailCache = ConcurrentHashMap<String, ImageBitmap>()

private suspend fun loadImage(url: String): ImageBitmap? = thumbnailCache[url] ?: withContext(Dispatchers.IO) {
    runCatching {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        val bytes = conn.inputStream.use { it.readBytes() }
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()?.also { thumbnailCache[url] = it }
}

/** 썸네일(없으면 영상 아이콘)과 오른쪽 아래 재생 시간. */
@Composable
fun Thumbnail(url: String?, durationSec: Double, modifier: Modifier = Modifier) {
    var image by remember(url) { mutableStateOf(url?.let { thumbnailCache[it] }) }
    LaunchedEffect(url) { if (url != null && image == null) image = loadImage(url) }

    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Icon(Icons.Filled.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (durationSec > 0) {
            Text(
                formatDuration(durationSec),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}
