package io.github.valeronm.breadcrumb.domain

import java.util.Locale

/**
 * The motion states we care about — Google's detected-activity constants reduced to a small set
 * (the GMS mapping lives in the `location` package, keeping this enum platform-free). An activity
 * carries only what the recorder decides with: a label, whether it records, and the [TrackGroup]
 * saying which switches split a track. Sampling cadence is deliberately not here — it is one
 * global setting, so an activity change never re-tunes GPS.
 */
enum class ActivityType(
    /** Whether we actively record GPS while in this state. */
    val recording: Boolean,
    /**
     * Activities in the same [TrackGroup] stay in one track when detection switches between them
     * mid-recording — a brief run during a walk (a common Activity-Recognition flip-flop) stays a
     * single track with a new segment, rather than fragmenting into walk/run/walk.
     */
    val trackGroup: TrackGroup,
) {
    WALKING(true, TrackGroup.FOOT),
    RUNNING(true, TrackGroup.FOOT),
    CYCLING(true, TrackGroup.BICYCLE),
    DRIVING(true, TrackGroup.VEHICLE),

    /** Never detected (activity recognition only sees IN_VEHICLE) — assigned by hand on the
     *  track page to mark rides where the user was a passenger. */
    TAXI(true, TrackGroup.VEHICLE),

    /** Any waterborne carrier — a ferry, a catamaran, a cruise ship. Never detected (a crossing
     *  reads as IN_VEHICLE, or as nothing at all when the vessel carries the phone rather than the
     *  other way round) — assigned by hand on the track page or the add-trip form. The name is the
     *  permanent stored code from when it meant only ferries; the name it goes by is what
     *  broadened, and it lives in the UI layer with the rest of the wording. */
    FERRY(true, TrackGroup.VEHICLE),

    /** Carried by the public network — bus, tram, subway, train. Never detected as itself (a ride
     *  reads as IN_VEHICLE, like any drive) — assigned by hand on the track page or the add-trip
     *  form. In the vehicle family on purpose: that is how its rides are detected and labeled
     *  live, and its rail ceiling is then the family's most permissive (see `TrackQuality`). */
    TRANSIT(true, TrackGroup.VEHICLE),

    /** Never detected — assigned by hand on the track page, and what the add-trip form writes for
     *  a flight entered after the fact. Its own [TrackGroup] on purpose: a flight's speed ceiling
     *  must not become any ground group's (see the ceilings in `TrackQuality`). */
    FLIGHT(true, TrackGroup.AIR),
    STILL(false, TrackGroup.STILL),
    UNKNOWN(true, TrackGroup.UNKNOWN),
    ;

    /** Whether [other] belongs in the same track as this activity when detection switches between them. */
    fun sharesTrackWith(other: ActivityType): Boolean = trackGroup == other.trackGroup

    companion object {
        /**
         * Indexed rather than scanned: this is asked once per row of every list that shows an
         * activity — a timeline of thousands of tracks, and again per recomposition — and a scan
         * pays an iterator and up to a dozen string compares each time to answer from a table that
         * cannot change.
         */
        private val byName = entries.associateBy { it.name }

        /** The [ActivityType] for a persisted `activityType` string (an [ActivityType.name]), or null. */
        fun ofName(stored: String): ActivityType? = byName[stored]

        /**
         * What a persisted `activityType` string reads as when it maps to no known activity — a
         * legacy code from an older install. Title-cased in [Locale.US] because the input is an
         * enum name, not language: the alternative is a Turkish device turning `DRIVING` into
         * `Drivıng`. A code that *does* map is named in the UI layer, where the wording lives.
         */
        fun legacyLabelFor(stored: String): String =
            stored.lowercase(Locale.US).replaceFirstChar { it.uppercase() }
    }
}

/** Coarse motion family used to decide whether an activity switch splits the track. */
enum class TrackGroup { FOOT, BICYCLE, VEHICLE, AIR, STILL, UNKNOWN }
