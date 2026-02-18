// data/model/VolumeWithStats.kt
package com.example.novel_summary.data.model

import androidx.room.ColumnInfo

data class VolumeWithStats(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "novelId") val novelId: Long,
    @ColumnInfo(name = "volumeName") val volumeName: String,
    @ColumnInfo(name = "chapter_count") val chapterCount: Int
)