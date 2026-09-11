package com.example.novel_summary.utils.audiobook

import android.content.Context

object AudiobookPlayerPrefs {

    private const val PREFS_NAME = "audiobook_player_prefs"
    private const val KEY_PLAYBACK_SPEED = "playback_speed"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPlaybackSpeed(context: Context): Float {
        return try {
            prefs(context).getFloat(KEY_PLAYBACK_SPEED, 1.0f)
        } catch (e: Exception) {
            1.0f
        }
    }

    fun savePlaybackSpeed(context: Context, speed: Float) {
        try {
            prefs(context)
                .edit()
                .putFloat(KEY_PLAYBACK_SPEED, speed)
                .apply()
        } catch (e: Exception) {
            // Ignore
        }
    }
}