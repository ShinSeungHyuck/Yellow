package com.example.yellow.data

import android.content.Context

object SettingsStore {
    private const val PREF = "yellow_prefs"
    private const val KEY_MIC_AUTO = "mic_auto_start"

    fun isMicAutoStart(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_MIC_AUTO, true)
    }

    fun setMicAutoStart(context: Context, value: Boolean) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_MIC_AUTO, value).apply()
    }
}
