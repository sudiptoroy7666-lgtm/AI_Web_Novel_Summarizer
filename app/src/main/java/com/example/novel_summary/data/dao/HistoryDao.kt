package com.example.novel_summary.data.dao

import androidx.room.*
import com.example.novel_summary.data.model.History
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Query("SELECT * FROM history_table ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<History>>

    @Query("SELECT * FROM history_table WHERE id = :id")
    suspend fun getHistoryById(id: Long): History?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: History)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistories(histories: List<History>)

    @Delete
    suspend fun deleteHistory(history: History)

    @Query("DELETE FROM history_table")
    suspend fun deleteAllHistory()

    @Query("DELETE FROM history_table WHERE id = :id")
    suspend fun deleteHistoryById(id: Long)
}