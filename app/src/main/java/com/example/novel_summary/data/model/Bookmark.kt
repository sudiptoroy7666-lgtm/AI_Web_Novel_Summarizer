package com.example.novel_summary.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks_table")
data class Bookmark(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)