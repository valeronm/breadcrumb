package com.valeronm.activitytracker.data

import android.content.Context

/** Tiny SharedPreferences-backed store for the app's persisted settings. */
object Settings {

    private const val FILE = "settings"
    private const val KEY_AUTO_RECORD = "auto_record"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether the user has armed automatic, activity-driven recording. */
    fun isAutoRecord(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_RECORD, false)

    fun setAutoRecord(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_RECORD, enabled).apply()
    }
}
