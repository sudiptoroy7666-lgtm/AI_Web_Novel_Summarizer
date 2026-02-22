package com.example.novel_summary.utils

import android.net.Uri
import java.net.URL

object UrlUtils {

    private const val GOOGLE_SEARCH_URL = "https://www.google.com/search?q="

    fun isValidUrl(input: String): Boolean {
        if (input.startsWith("http://") || input.startsWith("https://")) return true
        return try {
            URL(input)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun normalizeUrl(input: String): String {
        val trimmed = input.trim()

        // Check if it's already a valid URL
        if (isValidUrl(trimmed)) {
            return trimmed
        }

        // Check if it has a protocol
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }

        // Check if it's a domain without protocol
        if (trimmed.contains(".") && !trimmed.contains(" ")) {
            return "https://$trimmed"
        }

        // It's a search query - use Google search
        return "$GOOGLE_SEARCH_URL${Uri.encode(trimmed)}"
    }

    fun isSearchQuery(input: String): Boolean {
        val trimmed = input.trim()
        return trimmed.contains(" ") || !isValidUrl(trimmed)
    }

    fun extractDomain(url: String): String {
        return try {
            val uri = Uri.parse(url)
            uri.host?.replace("www.", "") ?: url
        } catch (e: Exception) {
            url
        }
    }

    fun extractTitleFromUrl(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val pathSegments = uri.pathSegments
            if (pathSegments.isNotEmpty()) {
                pathSegments.last().replace("-", " ").replace("_", " ")
            } else {
                extractDomain(url)
            }
        } catch (e: Exception) {
            extractDomain(url)
        }
    }
}