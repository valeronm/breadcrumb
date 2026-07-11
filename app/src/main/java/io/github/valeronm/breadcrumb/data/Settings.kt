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
    private const val KEY_GPS_GIVE_UP_SEC = "gps_give_up_sec"
    private const val KEY_USE_FUSED_PROVIDER = "use_fused_provider"
    private const val KEY_PLACES_SHOW_RARE_UNNAMED = "places_show_rare_unnamed"
    private const val KEY_IGNORE_REASON_BACKFILL_DONE = "ignore_reason_backfill_done"
    private const val KEY_KEEP_SCREEN_ON_CHARGING = "keep_screen_on_charging"
    private const val KEY_LAST_HEARTBEAT_MS = "last_heartbeat_ms"

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
    // cross-checks against real GNSS satellite status. Walks recorded via the fused provider are
    // exempt — indoors, network fixes are the only source. Raw-GPS tracks always keep the check:
    // the GNSS engine itself dead-reckons through signal loss. See LocationRecordingService.
    const val DEFAULT_REQUIRE_GNSS_FIX = true

    // No-fix give-up guard: if GPS runs this long without a single accepted fix (indoors on an
    // activity-recognition false positive, or parked underground), it's turned off until a
    // significant-motion trigger, a passive GPS fix, or an activity transition suggests trying
    // again. See LocationRecordingService. 0 = never give up.
    const val DEFAULT_GPS_GIVE_UP_SEC = 240

    // Which location source records tracks: raw platform GPS (default — no Wi-Fi scan overhead,
    // see 8ef8fa4) or Play Services' fused provider, which adds network positioning that can
    // produce fixes indoors. Kept switchable for field comparison at GNSS-opaque venues.
    const val DEFAULT_USE_FUSED_PROVIDER = false

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- Liveness heartbeat --------------------------------------------------

    /** When the app was last known alive (epoch ms, 0 = never). See LivenessRepository. */
    fun lastHeartbeatMs(context: Context): Long =
        prefs(context).getLong(KEY_LAST_HEARTBEAT_MS, 0L)

    fun setLastHeartbeatMs(context: Context, now: Long, sync: Boolean = false) {
        // sync commits on the caller's thread — for ACTION_SHUTDOWN, where the process is dying
        // and an async apply() may never hit disk.
        prefs(context).edit(commit = sync) { putLong(KEY_LAST_HEARTBEAT_MS, now) }
    }

    /** Keep the screen on while the app is open and the phone is charging (car-mount use). */
    fun keepScreenOnCharging(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_SCREEN_ON_CHARGING, false)

    fun setKeepScreenOnCharging(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_KEEP_SCREEN_ON_CHARGING, enabled) }
    }

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

    /** Max GPS-on time (seconds) with zero accepted fixes before giving up. 0 = never. */
    fun gpsGiveUpSec(context: Context): Int =
        prefs(context).getInt(KEY_GPS_GIVE_UP_SEC, DEFAULT_GPS_GIVE_UP_SEC)

    fun setGpsGiveUpSec(context: Context, value: Int) {
        prefs(context).edit { putInt(KEY_GPS_GIVE_UP_SEC, value) }
    }

    /** Whether to record via the fused provider (adds network positioning) instead of raw GPS. */
    fun useFusedProvider(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_FUSED_PROVIDER, DEFAULT_USE_FUSED_PROVIDER)

    fun setUseFusedProvider(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_USE_FUSED_PROVIDER, enabled) }
    }

    /** Places tab: also show unnamed clusters with fewer than 3 visits (hidden by default). */
    fun placesShowRareUnnamed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PLACES_SHOW_RARE_UNNAMED, false)

    fun setPlacesShowRareUnnamed(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_PLACES_SHOW_RARE_UNNAMED, enabled) }
    }

    // --- One-time migrations -------------------------------------------------

    /** Whether ignored points recorded before DB v5 have had their [IgnoreReason] backfilled. */
    fun isIgnoreReasonBackfillDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IGNORE_REASON_BACKFILL_DONE, false)

    fun setIgnoreReasonBackfillDone(context: Context) {
        prefs(context).edit { putBoolean(KEY_IGNORE_REASON_BACKFILL_DONE, true) }
    }
}
