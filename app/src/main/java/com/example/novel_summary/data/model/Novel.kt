package com.example.novel_summary.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "novels_table",
    indices = [Index(value = ["name"])]
)
data class Novel(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String
)