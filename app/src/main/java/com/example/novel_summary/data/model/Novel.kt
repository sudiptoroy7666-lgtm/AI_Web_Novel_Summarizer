package com.example.novel_summary.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "novels_table")
data class Novel(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String
)