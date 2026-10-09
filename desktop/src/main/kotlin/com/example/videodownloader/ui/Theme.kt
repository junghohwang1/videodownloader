package com.example.videodownloader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private val Brand = Color(0xFF1F6FEB)

private val LightColors = lightColorScheme(primary = Brand)
private val DarkColors = darkColorScheme(primary = Color(0xFF8AB4FF))

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

/** 창·트레이 아이콘: 파란 원 안의 흰 다운로드 화살표(어두운 작업 표시줄에서도 보이게). */
val AppIcon: ImageVector by lazy {
    ImageVector.Builder("AppIcon", 32.dp, 32.dp, 32f, 32f).apply {
        path(fill = SolidColor(Brand)) {
            moveTo(16f, 1f)
            arcTo(15f, 15f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 16f, y1 = 31f)
            arcTo(15f, 15f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 16f, y1 = 1f)
            close()
        }
        path(fill = SolidColor(Color.White)) {
            // 화살표 몸통과 머리
            moveTo(13.5f, 7f); lineTo(18.5f, 7f); lineTo(18.5f, 15f); lineTo(23f, 15f)
            lineTo(16f, 22f); lineTo(9f, 15f); lineTo(13.5f, 15f); close()
            // 받침대
            moveTo(8f, 23.5f); lineTo(24f, 23.5f); lineTo(24f, 26.5f); lineTo(8f, 26.5f); close()
        }
    }.build()
}
