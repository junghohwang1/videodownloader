package com.example.videodownloader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DownloadEntity::class, HistoryEntity::class, BookmarkEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        private val DEFAULT_BOOKMARKS = listOf(
            "Google" to "https://www.google.com",
            "네이버" to "https://m.naver.com",
            "Vimeo" to "https://vimeo.com",
            "Dailymotion" to "https://www.dailymotion.com",
        )

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN formatSpec TEXT")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "videodownloader.db")
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val now = System.currentTimeMillis()
                        DEFAULT_BOOKMARKS.forEachIndexed { i, (title, url) ->
                            db.execSQL(
                                "INSERT INTO bookmarks (title, url, createdAt) VALUES (?, ?, ?)",
                                arrayOf<Any>(title, url, now + i),
                            )
                        }
                    }
                })
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
