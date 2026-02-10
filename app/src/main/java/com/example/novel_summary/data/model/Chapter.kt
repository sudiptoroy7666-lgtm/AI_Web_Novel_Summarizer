package com.example.novel_summary.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "chapters_table",
    foreignKeys = [
        ForeignKey(
            entity = Volume::class,
            parentColumns = ["id"],
            childColumns = ["volumeId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class Chapter(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val volumeId: Long,
    val chapterName: String,
    val summaryText: String,
    val summaryType: String, // "short", "detailed", "very_detailed"
    val timestamp: Long = System.currentTimeMillis()
)