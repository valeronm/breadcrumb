package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.core.content.edit

/** Tiny SharedPreferences-backed store for the app's persisted settings. */
object Settings {

    private const val FILE = "settings"
    private const val KEY_AUTO_RECORD = "auto_record"

    private const val KEY_SAMPLING_MIN_INTERVAL_SEC = "sampling_min_interval_sec"
    private const val KEY_SAMPLING_MIN_DISTANCE_M = "sampling_min_distance_m"
    private const val KEY_TRACK_MIN_DURATION_SEC = "track_min_duration_sec"
    private const val KEY_TRACK_MIN_LENGTH_M = "track_min_length_m"
    private const val KEY_STITCH_RESUME_WINDOW_SEC = "stitch_resume_window_sec"
    private const val KEY_STITCH_RESUME_DISTANCE_M = "stitch_resume_distance_m"
    private const val KEY_BAD_FIX_BACKFILL_DONE = "bad_fix_backfill_done"
    private const val KEY_STITCH_MERGE_BACKFILL_DONE = "stitch_merge_backfill_done"

    const val DEFAULT_SAMPLING_MIN_INTERVAL_SEC = 5
    const val DEFAULT_SAMPLING_MIN_DISTANCE_M = 5
    const val DEFAULT_TRACK_MIN_DURATION_SEC = 30 // 0 = off
    const val DEFAULT_TRACK_MIN_LENGTH_M = 50 // 0 = off

    // Auto-pause/stitch: a brief stop keeps the track open and resumes into it when the same
    // activity returns within this time gap (and starting within this distance of where it paused).
    const val DEFAULT_STITCH_RESUME_WINDOW_SEC = 180 // 0 = always start a new track
    const val DEFAULT_STITCH_RESUME_DISTANCE_M = 100

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Whether the user has armed automatic, activity-driven recording. */
    fun isAutoRecord(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_RECORD, false)

    fun setAutoRecord(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_AUTO_RECORD, enabled) }
    }

    // --- Sampling (between points) ------------------------------------------

    /** Minimum time between recorded points, in seconds. */
    fun minIntervalSec(context: Context): Int =
        prefs(context).getInt(KEY_SAMPLING_MIN_INTERVAL_SEC, DEFAULT_SAMPLING_MIN_INTERVAL_SEC)

    fun setMinIntervalSec(context: Context, value: Int) {
        prefs(context).edit { putInt(KEY_SAMPLING_MIN_INTERVAL_SEC, value) }
    }

    /** Minimum displacement between recorded points, in metres. */
    fun minDistanceM(context: Context): Int =
        prefs(context).getInt(KEY_SAMPLING_MIN_DISTANCE_M, DEFAULT_SAMPLING_MIN_DISTANCE_M)

    fun setMinDistanceM(context: Context, value: Int) {
        prefs(context).edit { putInt(KEY_SAMPLING_MIN_DISTANCE_M, value) }
    }

    // --- Keep-a-track thresholds --------------------------------------------

    /** Tracks shorter than this duration (seconds) are discarded. 0 = no limit. */
    fun minTrackDurationSec(context: Context): Int =
        prefs(context).getInt(KEY_TRACK_MIN_DURATION_SEC, DEFAULT_TRACK_MIN_DURATION_SEC)

    fun setMinTrackDurationSec(context: Context, value: Int) {
        prefs(context).edit { putInt(KEY_TRACK_MIN_DURATION_SEC, value) }
    }

    /** Tracks shorter than this distance (metres) are discarded. 0 = no limit. */
    fun minTrackLengthM(context: Context): Int =
        prefs(context).getInt(KEY_TRACK_MIN_LENGTH_M, DEFAULT_TRACK_MIN_LENGTH_M)

    fun setMinTrackLengthM(context: Context, value: Int) {
        prefs(context).edit { putInt(KEY_TRACK_MIN_LENGTH_M, value) }
    }

    // --- Auto-pause / stitch -------------------------------------------------

    /** Max stop duration (seconds) that resumes the same track instead of starting a new one. */
    fun resumeWindowSec(context: Context): Int =
        prefs(context).getInt(KEY_STITCH_RESUME_WINDOW_SEC, DEFAULT_STITCH_RESUME_WINDOW_SEC)

    fun setResumeWindowSec(context: Context, value: Int) {
        prefs(context).edit { putInt(KEY_STITCH_RESUME_WINDOW_SEC, value) }
    }

    /** Max distance (metres) from where a track paused that still counts as resuming the same one. */
    fun resumeDistanceM(context: Context): Int =
        prefs(context).getInt(KEY_STITCH_RESUME_DISTANCE_M, DEFAULT_STITCH_RESUME_DISTANCE_M)

    fun setResumeDistanceM(context: Context, value: Int) {
        prefs(context).edit { putInt(KEY_STITCH_RESUME_DISTANCE_M, value) }
    }

    // --- One-time migrations -------------------------------------------------

    /** Whether existing tracks have been reprocessed to backfill the bad-fix flag (DB v2). */
    fun isBadFixBackfillDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BAD_FIX_BACKFILL_DONE, false)

    fun setBadFixBackfillDone(context: Context) {
        prefs(context).edit { putBoolean(KEY_BAD_FIX_BACKFILL_DONE, true) }
    }

    /** Whether existing fragmented tracks have been merged per the stitch rule (DB v3). */
    fun isStitchMergeBackfillDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STITCH_MERGE_BACKFILL_DONE, false)

    fun setStitchMergeBackfillDone(context: Context) {
        prefs(context).edit { putBoolean(KEY_STITCH_MERGE_BACKFILL_DONE, true) }
    }
}
