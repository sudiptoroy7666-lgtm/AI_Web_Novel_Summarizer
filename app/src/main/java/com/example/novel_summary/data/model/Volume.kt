package com.example.novel_summary.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "volumes_table",
    foreignKeys = [
        ForeignKey(
            entity = Novel::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class Volume(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val novelId: Long,
    val volumeName: String
)