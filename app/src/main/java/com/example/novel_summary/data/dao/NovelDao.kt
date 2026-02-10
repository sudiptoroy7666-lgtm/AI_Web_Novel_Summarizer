package com.example.novel_summary.data.dao

import androidx.room.*
import com.example.novel_summary.data.model.Novel
import kotlinx.coroutines.flow.Flow

@Dao
interface NovelDao {

    @Query("SELECT * FROM novels_table ORDER BY name ASC")
    fun getAllNovels(): Flow<List<Novel>>

    @Query("SELECT * FROM novels_table WHERE id = :id")
    suspend fun getNovelById(id: Long): Novel?

    @Query("SELECT * FROM novels_table WHERE name = :name")
    suspend fun getNovelByName(name: String): Novel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNovel(novel: Novel): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNovels(novels: List<Novel>)

    @Update
    suspend fun updateNovel(novel: Novel)

    @Delete
    suspend fun deleteNovel(novel: Novel)

    @Query("DELETE FROM novels_table WHERE id = :id")
    suspend fun deleteNovelById(id: Long)

    @Query("DELETE FROM novels_table")
    suspend fun deleteAllNovels()
}