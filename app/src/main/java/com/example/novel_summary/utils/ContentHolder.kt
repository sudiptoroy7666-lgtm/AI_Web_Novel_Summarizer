package com.example.novel_summary.utils

import android.content.Context
import com.example.novel_summary.App
import java.io.File

/**
 * Deprecated compatibility wrapper for older code paths.
 * The app now stores extracted summary content in a cache file instead of a static singleton.
 */
@Deprecated("Use SummaryContentStore instead.")
object ContentHolder {
    private const val LEGACY_CACHE_FILE = "content_holder_legacy.txt"

    fun setContent(content: String, url: String, title: String) {
        val context = App.appContext ?: return
        SummaryContentStore.writeContent(context, content, url, title)
    }

    fun getContent(): ContentData {
        val context = App.appContext ?: return ContentData("", "", "", 0L)
        val file = File(context.filesDir, LEGACY_CACHE_FILE)
        if (!file.exists()) return ContentData("", "", "", 0L)
        val text = file.readText(Charsets.UTF_8)
        val url = if (text.contains("\nURL:")) text.substringAfter("\nURL:").substringBefore("\nTITLE:") else ""
        val title = if (text.contains("\nTITLE:")) text.substringAfter("\nTITLE:").substringBefore("\nCONTENT:") else ""
        val content = if (text.contains("\nCONTENT:")) text.substringAfter("\nCONTENT:") else ""
        return ContentData(content = content, url = url, title = title, timestamp = System.currentTimeMillis())
    }

    fun hasContent(): Boolean = App.appContext?.let { SummaryContentStore.hasPendingSummary(it) } == true

    fun clear() {
        val context = App.appContext ?: return
        File(context.filesDir, LEGACY_CACHE_FILE).delete()
        SummaryContentStore.clearAll(context)
    }

    data class ContentData(
        val content: String,
        val url: String,
        val title: String,
        val timestamp: Long
    ) {
        val isValid: Boolean
            get() = content.isNotEmpty() && url.isNotEmpty()
    }
}