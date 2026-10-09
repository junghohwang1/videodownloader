package com.example.videodownloader.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AppSettings(
    /** 비어 있으면 기본 위치(사용자 동영상 폴더\VideoDownloader). */
    val downloadDir: String = "",
    val maxConcurrent: Int = 3,
    val notifyOnComplete: Boolean = true,
    /** 창을 닫으면 종료하지 않고 트레이로 내린다(다운로드 계속). */
    val minimizeToTray: Boolean = true,
)

class SettingsRepository(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val _settings = MutableStateFlow(
        runCatching { json.decodeFromString<AppSettings>(file.readText()) }.getOrElse { AppSettings() },
    )

    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun current(): AppSettings = _settings.value

    @Synchronized
    fun update(change: (AppSettings) -> AppSettings) {
        _settings.update(change)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(_settings.value))
    }
}
