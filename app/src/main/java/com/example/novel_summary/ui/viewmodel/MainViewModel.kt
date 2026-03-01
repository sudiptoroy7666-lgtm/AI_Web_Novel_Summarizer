package com.example.novel_summary.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.novel_summary.data.model.Bookmark
import com.example.novel_summary.data.model.History
import com.example.novel_summary.data.repository.BookmarkRepository
import com.example.novel_summary.data.repository.HistoryRepository
import com.example.novel_summary.data.repository.SummaryRepository
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val historyRepository: HistoryRepository
    private val bookmarkRepository: BookmarkRepository
    private val summaryRepository: SummaryRepository



    init {
        val database = com.example.novel_summary.App.database
        historyRepository = HistoryRepository(database.historyDao())
        bookmarkRepository = BookmarkRepository(database.bookmarkDao())
        summaryRepository = SummaryRepository(
            database.novelDao(),
            database.volumeDao(),
            database.chapterDao()
        )
    }

    fun saveToHistory(history: History) {
        viewModelScope.launch {
            try {
                historyRepository.insertHistory(history)
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to save history", e)
            }
        }
    }

    fun saveBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            try {
                bookmarkRepository.insertBookmark(bookmark)
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to save bookmark", e)
            }
        }
    }

    fun removeBookmark(url: String) {
        viewModelScope.launch {
            try {
                val bookmark = bookmarkRepository.getBookmarkByUrl(url)
                bookmark?.let { bookmarkRepository.deleteBookmark(it) }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to remove bookmark", e)
            }
        }
    }

    suspend fun getBookmarkByUrl(url: String): Bookmark? {
        return try {
            bookmarkRepository.getBookmarkByUrl(url)
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Failed to get bookmark by URL", e)
            null
        }
    }

    fun getAllHistory() = historyRepository.allHistory

    fun getAllBookmarks() = bookmarkRepository.allBookmarks

    // FIXED: Call getAllNovels() as a function with parentheses
    fun getAllNovels() = summaryRepository.getAllNovels()
}