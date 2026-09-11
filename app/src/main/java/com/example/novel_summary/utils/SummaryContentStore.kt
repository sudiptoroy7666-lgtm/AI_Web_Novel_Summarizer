package com.example.novel_summary.utils

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object SummaryContentStore {
    private const val CACHE_DIR_NAME = "summary_cache"
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    data class SummaryPayload(
        val id: String,
        val url: String,
        val title: String,
        val content: String,
        val createdAt: Long
    )

    fun writeContent(context: Context, content: String, url: String, title: String): String {
        cleanupOldFiles(context)
        val cacheDir = ensureCacheDir(context)
        val id = UUID.randomUUID().toString()
        val payload = SummaryPayload(
            id = id,
            url = url,
            title = title,
            content = content,
            createdAt = System.currentTimeMillis()
        )

        val contentFile = File(cacheDir, "$id.txt")
        val metaFile = File(cacheDir, "$id.meta")

        FileOutputStream(contentFile, false).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
        }

        val meta = buildString {
            append("id=").append(payload.id).append('\n')
            append("url=").append(payload.url).append('\n')
            append("title=").append(payload.title).append('\n')
            append("createdAt=").append(payload.createdAt).append('\n')
        }
        FileOutputStream(metaFile, false).use { output ->
            output.write(meta.toByteArray(Charsets.UTF_8))
        }

        return payload.id
    }

    fun readContent(context: Context, id: String): SummaryPayload? {
        val cacheDir = ensureCacheDir(context)
        val contentFile = File(cacheDir, "$id.txt")
        val metaFile = File(cacheDir, "$id.meta")
        if (!contentFile.exists() || !metaFile.exists()) return null

        return try {
            val content = contentFile.readText(Charsets.UTF_8)
            val meta = metaFile.readText(Charsets.UTF_8)
            val url = meta.substringAfter("url=").substringBefore('\n').ifBlank { "" }
            val title = meta.substringAfter("title=").substringBefore('\n').ifBlank { "Untitled" }
            val createdAt = meta.substringAfter("createdAt=").substringBefore('\n').toLongOrNull() ?: 0L
            SummaryPayload(
                id = id,
                url = url,
                title = title,
                content = content,
                createdAt = createdAt
            )
        } catch (_: Exception) {
            null
        }
    }

    fun readLatest(context: Context): SummaryPayload? {
        val cacheDir = ensureCacheDir(context)
        val files = cacheDir.listFiles { file -> file.name.endsWith(".meta") } ?: return null

        return files
            .asSequence()
            .mapNotNull { metaFile ->
                val id = metaFile.nameWithoutExtension
                val contentFile = File(cacheDir, "$id.txt")
                if (!contentFile.exists()) null else readContent(context, id)
            }
            .sortedByDescending { it.createdAt }
            .firstOrNull()
    }

    fun clearAll(context: Context) {
        val cacheDir = ensureCacheDir(context)
        cacheDir.listFiles()?.forEach { file -> file.delete() }
    }

    fun hasPendingSummary(context: Context): Boolean {
        val cacheDir = ensureCacheDir(context)
        return cacheDir.listFiles()?.any { it.name.endsWith(".meta") } == true
    }

    fun delete(context: Context, id: String) {
        val cacheDir = ensureCacheDir(context)
        File(cacheDir, "$id.txt").delete()
        File(cacheDir, "$id.meta").delete()
    }

    fun cleanupOldFiles(context: Context) {
        val now = System.currentTimeMillis()
        val cacheDir = ensureCacheDir(context)
        cacheDir.listFiles()?.forEach { file ->
            val age = now - file.lastModified()
            if (age > MAX_AGE_MS) {
                file.delete()
            }
        }
    }

    private fun ensureCacheDir(context: Context): File {
        val dir = File(context.filesDir, CACHE_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
