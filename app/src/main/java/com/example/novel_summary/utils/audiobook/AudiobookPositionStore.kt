package com.example.novel_summary.utils.audiobook

import android.content.Context

object AudiobookPositionStore {

    private const val PREFS_NAME = "audiobook_positions"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(bookId: String) = "position_$bookId"

    fun getPosition(context: Context, bookId: String): Pair<Int, Int> {
        return try {
            val raw = prefs(context).getString(key(bookId), "0:0") ?: "0:0"
            val parts = raw.split(":")

            val unitIndex = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val charOffset = parts.getOrNull(1)?.toIntOrNull() ?: 0

            unitIndex to charOffset
        } catch (e: Exception) {
            0 to 0
        }
    }

    fun savePosition(
        context: Context,
        bookId: String,
        unitIndex: Int,
        charOffset: Int,
        important: Boolean = false
    ) {
        try {
            val editor = prefs(context).edit()
            editor.putString(key(bookId), "$unitIndex:$charOffset")

            if (important) {
                editor.commit()
            } else {
                editor.apply()
            }
        } catch (e: Exception) {
            // Ignore. Position persistence should never crash playback.
        }
    }

    fun clearPosition(context: Context, bookId: String) {
        try {
            prefs(context).edit().remove(key(bookId)).apply()
        } catch (e: Exception) {
            // Ignore
        }
    }
}