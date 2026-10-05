package com.example.videodownloader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' ORDER BY createdAt ASC")
    suspend fun queued(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun active(): List<DownloadEntity>

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE downloads SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: DownloadStatus, error: String?)

    @Query(
        "UPDATE downloads SET downloadedBytes = :downloaded, totalBytes = :total, " +
            "segmentsDone = :segmentsDone, segmentsTotal = :segmentsTotal WHERE id = :id",
    )
    suspend fun setProgress(id: Long, downloaded: Long, total: Long, segmentsDone: Int, segmentsTotal: Int)

    @Query(
        "UPDATE downloads SET status = 'COMPLETED', error = NULL, completedAt = :completedAt, " +
            "filePath = :path, fileName = :name, totalBytes = :size, downloadedBytes = :size, " +
            "mimeType = :mimeType WHERE id = :id",
    )
    suspend fun markCompleted(id: Long, path: String, name: String, size: Long, mimeType: String?, completedAt: Long)

    @Query("UPDATE downloads SET filePath = :path, fileName = :name, isPrivate = :isPrivate WHERE id = :id")
    suspend fun updateLocation(id: Long, path: String, name: String, isPrivate: Boolean)

    @Query("UPDATE downloads SET status = 'PAUSED' WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun pauseInterrupted()
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HistoryEntity)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt ASC")
    fun observe(): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun insert(entity: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}
