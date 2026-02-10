package com.example.novel_summary.data.dao


import androidx.room.*
import com.example.novel_summary.data.model.Bookmark
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks_table ORDER BY timestamp DESC")
    fun getAllBookmarks(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks_table WHERE id = :id")
    suspend fun getBookmarkById(id: Long): Bookmark?

    @Query("SELECT * FROM bookmarks_table WHERE url = :url")
    suspend fun getBookmarkByUrl(url: String): Bookmark?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: Bookmark)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmarks(bookmarks: List<Bookmark>)

    @Delete
    suspend fun deleteBookmark(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks_table")
    suspend fun deleteAllBookmarks()

    @Query("DELETE FROM bookmarks_table WHERE id = :id")
    suspend fun deleteBookmarkById(id: Long)
}