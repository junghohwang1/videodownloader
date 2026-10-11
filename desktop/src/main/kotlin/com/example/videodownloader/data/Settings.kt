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
    /** 다운로드·자막 작업 중에 창을 닫으면 트레이에서 마저 하고, 끝나면 스스로 종료한다. 할 일이 없으면 바로 종료한다. */
    val minimizeToTray: Boolean = true,
    /** 내장 브라우저에서 광고(광고 서버 요청·광고 칸·유튜브 영상 광고)를 막는다. */
    val blockAds: Boolean = true,
    /** 영상 다운로드가 끝나면 자동으로 자막을 만든다(음성 인식). */
    val autoSubtitles: Boolean = false,
    /** 자막 만들기 때 소리를 1.5배속으로 줄여 빨리 인식한다(조금 덜 정확할 수 있음). */
    val fastSubtitles: Boolean = false,
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
