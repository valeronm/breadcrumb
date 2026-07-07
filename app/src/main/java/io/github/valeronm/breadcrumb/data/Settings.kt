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
    private const val KEY_TRACK_MIN_EXTENT_M = "track_min_extent_m"
    private const val KEY_STITCH_RESUME_WINDOW_SEC = "stitch_resume_window_sec"
    private const val KEY_ACCURACY_GATE_M = "accuracy_gate_m"
    private const val KEY_REQUIRE_GNSS_FIX = "require_gnss_fix"
    private const val KEY_START_CONFIRMATIONS = "start_confirmations"
    private const val KEY_ACTIVITY_POLL_ENABLED = "activity_poll_enabled"
    private const val KEY_ACTIVITY_POLL_INTERVAL_SEC = "activity_poll_interval_sec"

    const val DEFAULT_SAMPLING_MIN_INTERVAL_SEC = 5
    const val DEFAULT_SAMPLING_MIN_DISTANCE_M = 5
    const val DEFAULT_TRACK_MIN_DURATION_SEC = 30 // 0 = off
    const val DEFAULT_TRACK_MIN_LENGTH_M = 50 // 0 = off

    // Minimum spatial extent (bounding-box diagonal) for a track to be kept. Unlike length, which
    // accumulates GPS jitter while stationary, extent measures how far the track actually spread —
    // so a "walk" that never left a small blob (AR mislabelled standing still) is discarded. 0 = off.
    const val DEFAULT_TRACK_MIN_EXTENT_M = 50

    // Auto-pause/stitch: a brief stop keeps the track open and resumes into it when the same
    // activity returns within this time gap (the resumed run is a new GPX segment).
    const val DEFAULT_STITCH_RESUME_WINDOW_SEC = 180 // 0 = always start a new track

    // Fixes whose reported accuracy radius is at least this (metres) are flagged noisy and excluded.
    const val DEFAULT_ACCURACY_GATE_M = 50

    // Reject fused fixes with no recent satellite backing (network/dead-reckoning fabrications, e.g.
    // in a tunnel). These can report good accuracy, so the accuracy gate alone misses them; this
    // cross-checks against real GNSS satellite status. See LocationRecordingService.
    const val DEFAULT_REQUIRE_GNSS_FIX = true

    // How many consecutive moving readings of the same activity are needed before a *new* track is
    // opened (and GPS spun up). Filters lone high-confidence blips that revert to STILL on the next
    // poll. 1 = start instantly on the first reading (old behaviour). Resuming a paused track is
    // never gated.
    const val DEFAULT_START_CONFIRMATIONS = 2

    const val DEFAULT_ACTIVITY_POLL_ENABLED = false

    // How often the poll re-reads activity while armed. Also the age past which a (usually replayed)
    // transition is treated as stale — a poll refreshes the reading at least this often.
    const val DEFAULT_ACTIVITY_POLL_INTERVAL_SEC = 30

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

    /** Tracks whose bounding-box diagonal is under this (metres) are discarded. 0 = no limit. */
    fun minTrackExtentM(context: Context): Int =
        prefs(context).getInt(KEY_TRACK_MIN_EXTENT_M, DEFAULT_TRACK_MIN_EXTENT_M)

    fun setMinTrackExtentM(context: Context, value: Int) {
        prefs(context).edit { putInt(KEY_TRACK_MIN_EXTENT_M, value) }
    }

    // --- Auto-pause / stitch -------------------------------------------------

    /** Max stop duration (seconds) that resumes the same track instead of starting a new one. */
    fun resumeWindowSec(context: Context): Int =
        prefs(context).getInt(KEY_STITCH_RESUME_WINDOW_SEC, DEFAULT_STITCH_RESUME_WINDOW_SEC)

    fun setResumeWindowSec(context: Context, value: Int) {
        prefs(context).edit { putInt(KEY_STITCH_RESUME_WINDOW_SEC, value) }
    }

    /** Accuracy radius (metres) at/above which a fix is flagged noisy and excluded from new tracks. */
    fun accuracyGateM(context: Context): Int =
        prefs(context).getInt(KEY_ACCURACY_GATE_M, DEFAULT_ACCURACY_GATE_M)

    fun setAccuracyGateM(context: Context, value: Int) {
        prefs(context).edit { putInt(KEY_ACCURACY_GATE_M, value) }
    }

    /** Whether to drop fused fixes lacking recent satellite backing (network/dead-reckoning only). */
    fun requireGnssFix(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REQUIRE_GNSS_FIX, DEFAULT_REQUIRE_GNSS_FIX)

    fun setRequireGnssFix(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_REQUIRE_GNSS_FIX, enabled) }
    }

    /** Consecutive moving readings required before opening a new track. 1 = start instantly. */
    fun startConfirmations(context: Context): Int =
        prefs(context).getInt(KEY_START_CONFIRMATIONS, DEFAULT_START_CONFIRMATIONS)

    fun setStartConfirmations(context: Context, value: Int) {
        prefs(context).edit { putInt(KEY_START_CONFIRMATIONS, value) }
    }

    /** Whether the recorder re-polls the activity snapshot while armed (off = transitions only). */
    fun activityPollEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACTIVITY_POLL_ENABLED, DEFAULT_ACTIVITY_POLL_ENABLED)

    fun setActivityPollEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ACTIVITY_POLL_ENABLED, enabled) }
    }

    /** How often (seconds) the poll re-reads activity, and the stale-transition age cutoff. */
    fun activityPollIntervalSec(context: Context): Int =
        prefs(context).getInt(KEY_ACTIVITY_POLL_INTERVAL_SEC, DEFAULT_ACTIVITY_POLL_INTERVAL_SEC)

    fun setActivityPollIntervalSec(context: Context, value: Int) {
        prefs(context).edit { putInt(KEY_ACTIVITY_POLL_INTERVAL_SEC, value) }
    }
}
