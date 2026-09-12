package com.example.novel_summary.utils.audiobook

import java.util.UUID

data class AudiobookMeta(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val fileName: String,
    val charCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)