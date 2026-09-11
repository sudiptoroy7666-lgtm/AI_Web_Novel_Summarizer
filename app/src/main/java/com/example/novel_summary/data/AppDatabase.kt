package com.example.novel_summary.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.novel_summary.data.dao.*
import com.example.novel_summary.data.model.*

@Database(
    entities = [
        History::class,
        Bookmark::class,
        Novel::class,
        Volume::class,
        Chapter::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun novelDao(): NovelDao
    abstract fun volumeDao(): VolumeDao
    abstract fun chapterDao(): ChapterDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_table_url ON bookmarks_table(url)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_history_table_url ON history_table(url)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_novels_table_name ON novels_table(name)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_volumes_table_novelId ON volumes_table(novelId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_volumes_table_novelId_volumeName ON volumes_table(novelId, volumeName)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chapters_table_volumeId ON chapters_table(volumeId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chapters_table_volumeId_chapterName ON chapters_table(volumeId, chapterName)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "webnovel_summarizer_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}