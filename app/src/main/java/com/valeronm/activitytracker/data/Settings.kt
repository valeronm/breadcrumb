package com.valeronm.activitytracker.data

import android.content.Context

/** Tiny SharedPreferences-backed store for the app's persisted settings. */
object Settings {

    private const val FILE = "settings"
    private const val KEY_AUTO_RECORD = "auto_record"
    private const val KEY_MIN_INTERVAL_SEC = "min_interval_sec"
    private const val KEY_MIN_DISTANCE_M = "min_distance_m"
    private const val KEY_MIN_POINTS = "min_points"
    private const val KEY_MIN_DURATION_SEC = "min_duration_sec"
    private const val KEY_MIN_LENGTH_M = "min_length_m"

    // Defaults preserve the previous hard-coded behaviour.
    const val DEFAULT_MIN_INTERVAL_SEC = 5
    const val DEFAULT_MIN_DISTANCE_M = 5
    const val DEFAULT_MIN_POINTS = 4
    const val DEFAULT_MIN_DURATION_SEC = 0 // 0 = off
    const val DEFAULT_MIN_LENGTH_M = 0 // 0 = off

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether the user has armed automatic, activity-driven recording. */
    fun isAutoRecord(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_RECORD, false)

    fun setAutoRecord(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_RECORD, enabled).apply()
    }

    // --- Sampling (between points) ------------------------------------------

    /** Minimum time between recorded points, in seconds. */
    fun minIntervalSec(context: Context): Int =
        prefs(context).getInt(KEY_MIN_INTERVAL_SEC, DEFAULT_MIN_INTERVAL_SEC)

    fun setMinIntervalSec(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_MIN_INTERVAL_SEC, value).apply()
    }

    /** Minimum displacement between recorded points, in metres. */
    fun minDistanceM(context: Context): Int =
        prefs(context).getInt(KEY_MIN_DISTANCE_M, DEFAULT_MIN_DISTANCE_M)

    fun setMinDistanceM(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_MIN_DISTANCE_M, value).apply()
    }

    // --- Keep-a-track thresholds --------------------------------------------

    /** Tracks with fewer points than this are discarded when recording ends. */
    fun minTrackPoints(context: Context): Int =
        prefs(context).getInt(KEY_MIN_POINTS, DEFAULT_MIN_POINTS)

    fun setMinTrackPoints(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_MIN_POINTS, value).apply()
    }

    /** Tracks shorter than this duration (seconds) are discarded. 0 = no limit. */
    fun minTrackDurationSec(context: Context): Int =
        prefs(context).getInt(KEY_MIN_DURATION_SEC, DEFAULT_MIN_DURATION_SEC)

    fun setMinTrackDurationSec(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_MIN_DURATION_SEC, value).apply()
    }

    /** Tracks shorter than this distance (metres) are discarded. 0 = no limit. */
    fun minTrackLengthM(context: Context): Int =
        prefs(context).getInt(KEY_MIN_LENGTH_M, DEFAULT_MIN_LENGTH_M)

    fun setMinTrackLengthM(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_MIN_LENGTH_M, value).apply()
    }
}
