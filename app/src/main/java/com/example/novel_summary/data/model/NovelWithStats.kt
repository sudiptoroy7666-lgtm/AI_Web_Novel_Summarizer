// data/model/NovelWithStats.kt
package com.example.novel_summary.data.model

import androidx.room.ColumnInfo

data class NovelWithStats(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "volume_count") val volumeCount: Int
)