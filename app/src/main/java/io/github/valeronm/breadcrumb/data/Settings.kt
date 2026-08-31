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
    private const val KEY_PLACES_SHOW_RARE_STOPS = "places_show_rare_stops"
    private const val KEY_PLACES_VIEW_MAP = "places_view_map"
    private const val KEY_TIMELINE_VIEW_MAP = "timeline_view_map"
    private const val KEY_PLACES_SORT = "places_sort"
    private const val KEY_KEEP_SCREEN_ON_CHARGING = "keep_screen_on_charging"
    private const val KEY_UNIT_CHOICE = "unit_choice"
    private const val KEY_DISARMED_SINCE_MS = "disarmed_since_ms"
    private const val KEY_APP_LOCK = "app_lock"
    private const val KEY_APP_LOCK_GRACE_SEC = "app_lock_grace_sec"
    private const val KEY_APP_LOCK_TRUSTS_KEYGUARD = "app_lock_trusts_keyguard"
    private const val KEY_BLOCK_SCREENSHOTS = "block_screenshots"
    private const val KEY_ONLINE_PLACE_SEARCH = "online_place_search"
    private const val KEY_DEPARTURE_FENCE = "departure_fence"
    private const val KEY_DEPARTURE_MOTION = "departure_motion"
    private const val KEY_DEPARTURE_CONTINUOUS = "departure_continuous"
    private const val KEY_ASKED_PERMISSIONS = "asked_permissions"

    // The key string doesn't match the edge-stay name and must stay that way: a renamed key reads
    // back 0 on every installed device and re-walks the whole history for nothing.
    private const val KEY_EDGE_STAY_RULE_VERSION = "review_mark_rule_version"

    private const val KEY_STATS_RULE_VERSION = "stats_rule_version"
    private const val KEY_DERIVED_LOGIC_VERSION = "derived_logic_version"

    const val DEFAULT_SAMPLING_MIN_INTERVAL_SEC = 5
    const val DEFAULT_SAMPLING_MIN_DISTANCE_M = 5
    const val DEFAULT_TRACK_MIN_DURATION_SEC = 30 // 0 = off
    const val DEFAULT_TRACK_MIN_LENGTH_M = 50 // 0 = off

    // Minimum spatial extent (bounding-box diagonal) for a track to be kept. Unlike length, which
    // accumulates GPS jitter while stationary, extent measures how far the track actually spread —
    // so a "walk" that never left a small blob (AR mislabeled standing still) is discarded. 0 = off.
    const val DEFAULT_TRACK_MIN_EXTENT_M = 50

    // Stitch: a stop closes the track, and movement returning in the same motion family within this
    // window records into that same row rather than opening a second one beside it (the resumed run
    // is a new GPX segment). Measured from the track's last point, not its stored end.
    const val DEFAULT_STITCH_RESUME_WINDOW_SEC = 180 // 0 = always start a new track

    // Fixes whose reported accuracy radius is at least this (meters) are flagged noisy and excluded.
    const val DEFAULT_ACCURACY_GATE_M = 50

    // Reject fixes with no recent satellite backing (dead-reckoning fabrications, e.g. in a
    // tunnel — the GNSS engine dead-reckons through signal loss). These can report good accuracy,
    // so the accuracy gate alone misses them; this cross-checks against real GNSS satellite
    // status. See LocationRecordingService.
    const val DEFAULT_REQUIRE_GNSS_FIX = true

    // No-fix give-up guard: if GPS runs this long without a single accepted fix (indoors on an
    // activity-recognition false positive, or parked underground), it's turned off until a
    // significant-motion trigger, a passive GPS fix, or an activity transition suggests trying
    // again. See LocationRecordingService. 0 = never give up.
    const val DEFAULT_GPS_GIVE_UP_SEC = 240

    // How long the app may sit in the background before the lock re-engages. Not zero: the app
    // sends itself to the background through its own flows — the document and folder pickers, the
    // permission deep-link into system settings, the maps-app action from a place — and prompting
    // again on the way back from each of those is what makes a lock unusable.
    const val DEFAULT_APP_LOCK_GRACE_SEC = 30

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- Recorder disarm bookkeeping -----------------------------------------

    /**
     * When the recorder was last turned off with nothing since (epoch ms), or null while armed —
     * the instant the timeline's trailing stay closes at, the app attesting nothing past a disarm.
     * Written only by [setAutoRecord], in the same edit as the flag.
     */
    fun disarmedSinceMs(context: Context): Long? =
        prefs(context).getLong(KEY_DISARMED_SINCE_MS, 0L).takeIf { it > 0 }

    /** Keep the screen on while the app is open and the phone is charging (car-mount use). */
    fun keepScreenOnCharging(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_SCREEN_ON_CHARGING, false)

    fun setKeepScreenOnCharging(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_KEEP_SCREEN_ON_CHARGING, enabled) }
    }

    /** Whether the add-trip search may send typed queries to the online geocoder
     *  ([OnlinePlaceSearch]) — the one feature that puts anything but tile fetches on the wire. */
    fun isOnlinePlaceSearch(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONLINE_PLACE_SEARCH, true)

    fun setOnlinePlaceSearch(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ONLINE_PLACE_SEARCH, enabled) }
    }

    /**
     * Permissions this install has put a system dialog up for.
     *
     * Kept because `shouldShowRequestPermissionRationale` answers false in two opposite situations —
     * a permission never asked for, and one refused twice and now unaskable — and nothing else
     * separates them. The first needs a plain request; the second needs a trip to system settings,
     * and offering it a request instead is a button that does nothing at all.
     */
    fun askedPermissions(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_ASKED_PERMISSIONS, emptySet()).orEmpty()

    fun markPermissionsAsked(context: Context, permissions: Collection<String>) {
        // A fresh set, never the one just read: SharedPreferences hands back its own instance and
        // mutating it leaves the stored value and the in-memory cache disagreeing.
        val merged = askedPermissions(context) + permissions
        prefs(context).edit { putStringSet(KEY_ASKED_PERMISSIONS, merged) }
    }

    /** Whether the user has armed automatic, activity-driven recording. */
    fun isAutoRecord(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_RECORD, false)

    /**
     * Arms or disarms, stamping [disarmedSinceMs] in the same write: a disarm that landed without
     * its instant would leave the trailing stay stretching to the clock while nothing records. A
     * repeat of the current state writes nothing, so a second disarm keeps the first's instant.
     */
    fun setAutoRecord(context: Context, enabled: Boolean) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_AUTO_RECORD, false) == enabled) return
        // Synchronous commit: this is the flag BootReceiver and the watchdog consult to decide
        // whether a dead service should be resurrected. An async apply() lost to a process kill
        // silently drops the user's arm (recording never resumes) or their disarm (recording
        // comes back from the dead). It changes on a user tap, so the disk write is rare.
        prefs.edit(commit = true) {
            putBoolean(KEY_AUTO_RECORD, enabled)
            if (enabled) remove(KEY_DISARMED_SINCE_MS) else putLong(KEY_DISARMED_SINCE_MS, System.currentTimeMillis())
        }
    }

    // --- Sampling (between points) ------------------------------------------

    /** Minimum time between recorded points, in seconds. */
    fun minIntervalSec(context: Context): Int =
        prefs(context).getInt(KEY_SAMPLING_MIN_INTERVAL_SEC, DEFAULT_SAMPLING_MIN_INTERVAL_SEC)

    fun setMinIntervalSec(context: Context, value: Int) {
        prefs(context).edit { putInt(KEY_SAMPLING_MIN_INTERVAL_SEC, value) }
    }

    /** Minimum displacement between recorded points, in meters. */
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

    /** Tracks shorter than this distance (meters) are discarded. 0 = no limit. */
    fun minTrackLengthM(context: Context): Int =
        prefs(context).getInt(KEY_TRACK_MIN_LENGTH_M, DEFAULT_TRACK_MIN_LENGTH_M)

    fun setMinTrackLengthM(context: Context, value: Int) {
        prefs(context).edit { putInt(KEY_TRACK_MIN_LENGTH_M, value) }
    }

    /** Tracks whose bounding-box diagonal is under this (meters) are discarded. 0 = no limit. */
    fun minTrackExtentM(context: Context): Int =
        prefs(context).getInt(KEY_TRACK_MIN_EXTENT_M, DEFAULT_TRACK_MIN_EXTENT_M)

    fun setMinTrackExtentM(context: Context, value: Int) {
        prefs(context).edit { putInt(KEY_TRACK_MIN_EXTENT_M, value) }
    }

    // --- Stitch --------------------------------------------------------------

    /** Max stop duration (seconds) that keeps recording into the last track rather than a new one. */
    fun stitchWindowSec(context: Context): Int =
        prefs(context).getInt(KEY_STITCH_RESUME_WINDOW_SEC, DEFAULT_STITCH_RESUME_WINDOW_SEC)

    fun setStitchWindowSec(context: Context, value: Int) {
        prefs(context).edit { putInt(KEY_STITCH_RESUME_WINDOW_SEC, value) }
    }

    /** Accuracy radius (meters) at/above which a fix is flagged noisy and excluded from new tracks. */
    fun accuracyGateM(context: Context): Int =
        prefs(context).getInt(KEY_ACCURACY_GATE_M, DEFAULT_ACCURACY_GATE_M)

    fun setAccuracyGateM(context: Context, value: Int) {
        prefs(context).edit { putInt(KEY_ACCURACY_GATE_M, value) }
    }

    /** Whether to drop fixes lacking recent satellite backing (dead-reckoning fabrications). */
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

    // --- Departure triggers --------------------------------------------------
    //
    // Three independent ways to notice the phone has left a stop, because activity detection only
    // describes the body: a passenger sits still, so a train, a taxi and a bus can all be announced
    // as nothing at all. They are switches rather than a mode because they cost differently and
    // fail differently, and no ordering of them is right on every phone.

    /** Geofence at the last stop. Free, system-held across process death, and reports minutes late. */
    fun departureFence(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEPARTURE_FENCE, true)

    fun setDepartureFence(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_DEPARTURE_FENCE, enabled) }
    }

    /**
     * A burst of coarse positions after the hardware motion sensor fires. On by default: the sensor
     * costs nothing until the phone moves, and walking — the case that would trigger it most — is
     * already recording rather than waiting, so the burst is rare.
     */
    fun departureMotion(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEPARTURE_MOTION, true)

    fun setDepartureMotion(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_DEPARTURE_MOTION, enabled) }
    }

    /**
     * A standing coarse-position request for the whole time nothing is recording. Off by default,
     * and it is the only one of the three that needs defending: idle is the state the recorder
     * spends most of its life in, so this is the one trigger whose cost is paid all day and mostly
     * by a phone that is going nowhere.
     */
    fun departureContinuous(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEPARTURE_CONTINUOUS, false)

    fun setDepartureContinuous(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_DEPARTURE_CONTINUOUS, enabled) }
    }

    // --- Privacy -------------------------------------------------------------

    /** Whether opening the app asks for the fingerprint or device PIN. Gates the UI only —
     *  recording, the watchdog and the boot resume run whether or not the app is locked. */
    fun appLock(context: Context): Boolean =
        prefs(context).getBoolean(KEY_APP_LOCK, false)

    fun setAppLock(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_APP_LOCK, enabled) }
    }

    /** Background time (seconds) after which the app lock re-engages. 0 = every time. */
    fun appLockGraceSec(context: Context): Int =
        prefs(context).getInt(KEY_APP_LOCK_GRACE_SEC, DEFAULT_APP_LOCK_GRACE_SEC)

    fun setAppLockGraceSec(context: Context, value: Int) {
        prefs(context).edit { putInt(KEY_APP_LOCK_GRACE_SEC, value) }
    }

    /**
     * Whether dismissing the phone's own keyguard counts as unlocking the app too — off by default,
     * because with it on the app lock stops being a second factor: anyone who can open the phone can
     * open the history, which for a shared PIN or a second enrolled face is a real person and not a
     * hypothetical one. On, the app is left alone when the keyguard has been through since it was
     * last seen, and the phone handed over already unlocked is unaffected — no keyguard was dismissed,
     * so [appLockGraceSec] still re-locks it.
     */
    fun appLockTrustsKeyguard(context: Context): Boolean =
        prefs(context).getBoolean(KEY_APP_LOCK_TRUSTS_KEYGUARD, false)

    fun setAppLockTrustsKeyguard(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_APP_LOCK_TRUSTS_KEYGUARD, enabled) }
    }

    /** Whether the window is marked secure, which blocks screenshots *and* the recents thumbnail.
     *  Independent of [appLock]: the thumbnail is a map of where its user was, and the price of
     *  hiding it is losing your own screenshots. */
    fun blockScreenshots(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLOCK_SCREENSHOTS, false)

    fun setBlockScreenshots(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_BLOCK_SCREENSHOTS, enabled) }
    }

    /** Display units, stored by [io.github.valeronm.breadcrumb.util.UnitChoice] name (the UI owns
     *  the enum; unknown names fall back to following the locale). */
    fun unitChoice(context: Context): String? =
        prefs(context).getString(KEY_UNIT_CHOICE, null)

    fun setUnitChoice(context: Context, name: String) {
        prefs(context).edit { putString(KEY_UNIT_CHOICE, name) }
    }

    /** Places map: also show clusters with fewer than 3 visits, named or not (hidden by default). */
    fun placesShowRareStops(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PLACES_SHOW_RARE_STOPS, false)

    fun setPlacesShowRareStops(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_PLACES_SHOW_RARE_STOPS, enabled) }
    }

    /** Places tab: whether the map view (vs the sorted list) was last selected. */
    fun placesViewMap(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PLACES_VIEW_MAP, true)

    fun setPlacesViewMap(context: Context, map: Boolean) {
        prefs(context).edit { putBoolean(KEY_PLACES_VIEW_MAP, map) }
    }

    /** Timeline tab: whether the day-map view (vs the list) was last selected. Defaults to the
     *  list, the timeline's denser reading. */
    fun timelineViewMap(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TIMELINE_VIEW_MAP, false)

    fun setTimelineViewMap(context: Context, map: Boolean) {
        prefs(context).edit { putBoolean(KEY_TIMELINE_VIEW_MAP, map) }
    }

    /** Places list sort, stored by enum name (the UI owns the enum; unknown names fall back). */
    fun placesSort(context: Context): String? =
        prefs(context).getString(KEY_PLACES_SORT, null)

    fun setPlacesSort(context: Context, name: String) {
        prefs(context).edit { putString(KEY_PLACES_SORT, name) }
    }

    /** Which [io.github.valeronm.breadcrumb.domain.EdgeStayDetector.RULE_VERSION] the stored
     *  edge-stay ignores were computed with; 0 = never swept. */
    fun edgeStayRuleVersion(context: Context): Int =
        prefs(context).getInt(KEY_EDGE_STAY_RULE_VERSION, 0)

    fun setEdgeStayRuleVersion(context: Context, version: Int) {
        prefs(context).edit { putInt(KEY_EDGE_STAY_RULE_VERSION, version) }
    }

    /** Which [TrackStats.RULE_VERSION] the stored track aggregates were computed with;
     *  0 = never swept. */
    fun statsRuleVersion(context: Context): Int =
        prefs(context).getInt(KEY_STATS_RULE_VERSION, 0)

    fun setStatsRuleVersion(context: Context, version: Int) {
        prefs(context).edit { putInt(KEY_STATS_RULE_VERSION, version) }
    }

    /** Which [DerivationStore.LOGIC_VERSION] the stored stay/place rows were derived by;
     *  0 = never derived, which is every install until the tables are first filled. */
    fun derivedLogicVersion(context: Context): Int =
        prefs(context).getInt(KEY_DERIVED_LOGIC_VERSION, 0)

    fun setDerivedLogicVersion(context: Context, version: Int) {
        prefs(context).edit { putInt(KEY_DERIVED_LOGIC_VERSION, version) }
    }
}
