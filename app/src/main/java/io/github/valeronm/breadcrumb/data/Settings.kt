package io.github.valeronm.breadcrumb.data

import android.content.Context

/** Tiny SharedPreferences-backed store for the app's persisted settings. */
object Settings {

    private const val FILE = "settings"
    private const val KEY_AUTO_RECORD = "auto_record"

    private const val KEY_SAMPLING_MIN_INTERVAL_SEC = "sampling_min_interval_sec"
    private const val KEY_SAMPLING_MIN_DISTANCE_M = "sampling_min_distance_m"
    private const val KEY_TRACK_MIN_DURATION_SEC = "track_min_duration_sec"
    private const val KEY_TRACK_MIN_LENGTH_M = "track_min_length_m"

    const val DEFAULT_SAMPLING_MIN_INTERVAL_SEC = 5
    const val DEFAULT_SAMPLING_MIN_DISTANCE_M = 5
    const val DEFAULT_TRACK_MIN_DURATION_SEC = 30 // 0 = off
    const val DEFAULT_TRACK_MIN_LENGTH_M = 50 // 0 = off

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
        prefs(context).getInt(KEY_SAMPLING_MIN_INTERVAL_SEC, DEFAULT_SAMPLING_MIN_INTERVAL_SEC)

    fun setMinIntervalSec(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_SAMPLING_MIN_INTERVAL_SEC, value).apply()
    }

    /** Minimum displacement between recorded points, in metres. */
    fun minDistanceM(context: Context): Int =
        prefs(context).getInt(KEY_SAMPLING_MIN_DISTANCE_M, DEFAULT_SAMPLING_MIN_DISTANCE_M)

    fun setMinDistanceM(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_SAMPLING_MIN_DISTANCE_M, value).apply()
    }

    // --- Keep-a-track thresholds --------------------------------------------

    /** Tracks shorter than this duration (seconds) are discarded. 0 = no limit. */
    fun minTrackDurationSec(context: Context): Int =
        prefs(context).getInt(KEY_TRACK_MIN_DURATION_SEC, DEFAULT_TRACK_MIN_DURATION_SEC)

    fun setMinTrackDurationSec(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_TRACK_MIN_DURATION_SEC, value).apply()
    }

    /** Tracks shorter than this distance (metres) are discarded. 0 = no limit. */
    fun minTrackLengthM(context: Context): Int =
        prefs(context).getInt(KEY_TRACK_MIN_LENGTH_M, DEFAULT_TRACK_MIN_LENGTH_M)

    fun setMinTrackLengthM(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_TRACK_MIN_LENGTH_M, value).apply()
    }
}
