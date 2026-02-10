package com.example.novel_summary.data.dao


import androidx.room.*
import com.example.novel_summary.data.model.Chapter
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {

    @Query("SELECT * FROM chapters_table WHERE volumeId = :volumeId ORDER BY timestamp DESC")
    fun getChaptersByVolumeId(volumeId: Long): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters_table WHERE id = :id")
    suspend fun getChapterById(id: Long): Chapter?

    @Query("SELECT * FROM chapters_table WHERE volumeId = :volumeId AND chapterName = :chapterName")
    suspend fun getChapterByName(volumeId: Long, chapterName: String): Chapter?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapter(chapter: Chapter): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<Chapter>)

    @Update
    suspend fun updateChapter(chapter: Chapter)

    @Delete
    suspend fun deleteChapter(chapter: Chapter)

    @Query("DELETE FROM chapters_table WHERE id = :id")
    suspend fun deleteChapterById(id: Long)

    @Query("DELETE FROM chapters_table WHERE volumeId = :volumeId")
    suspend fun deleteChaptersByVolumeId(volumeId: Long)

    @Query("DELETE FROM chapters_table")
    suspend fun deleteAllChapters()
}