package com.example.novel_summary.utils.customai

import java.util.UUID

data class CustomAiApi(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val maxChars: Int = 100_000
)