package com.example.videodownloader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** FILE: 단일 파일, HLS: m3u8, YOUTUBE: 유튜브(다운로드 시점에 스트림 주소를 다시 조회) */
enum class MediaKind { FILE, HLS, YOUTUBE }

enum class DownloadStatus {
    QUEUED, RUNNING, PAUSED, COMPLETED, FAILED;

    val isActive: Boolean get() = this == QUEUED || this == RUNNING
}

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val pageUrl: String? = null,
    val title: String,
    val fileName: String,
    val filePath: String,
    val mimeType: String? = null,
    val kind: MediaKind,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val segmentsTotal: Int = 0,
    val segmentsDone: Int = 0,
    val referer: String? = null,
    val userAgent: String? = null,
    /** 유튜브 형식 키(YtSpec). 다른 종류는 null. */
    val formatSpec: String? = null,
    val isPrivate: Boolean = false,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
)

/** 0..1 진행률. HLS 는 세그먼트 수, 일반 파일은 바이트 기준. 알 수 없으면 null. */
fun DownloadEntity.progressFraction(): Float? = when {
    segmentsTotal > 0 -> segmentsDone.toFloat() / segmentsTotal
    totalBytes > 0 -> (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    else -> null
}

@Entity(tableName = "history", indices = [Index(value = ["url"], unique = true)])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis(),
)
