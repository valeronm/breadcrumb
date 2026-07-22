package io.github.valeronm.breadcrumb.data

import com.google.android.gms.location.DetectedActivity
import java.util.Locale

/**
 * The motion states we care about — Google's [DetectedActivity] constants reduced to a small set.
 * An activity carries only what the recorder decides with: a label, whether it records at all, and
 * the [TrackGroup] that says which switches split a track. Sampling cadence is deliberately not
 * here: it is one global setting, so an activity change never re-tunes GPS.
 */
enum class ActivityType(
    val label: String,
    /** Whether we actively record GPS while in this state. */
    val recording: Boolean,
    /**
     * Activities in the same [TrackGroup] stay in one track when detection switches between them
     * mid-recording — a brief run during a walk (a common Activity-Recognition flip-flop) stays a
     * single track with a new segment, rather than fragmenting into walk/run/walk.
     */
    val trackGroup: TrackGroup,
) {
    WALKING("Walking", true, TrackGroup.FOOT),
    RUNNING("Running", true, TrackGroup.FOOT),
    CYCLING("Cycling", true, TrackGroup.BICYCLE),
    DRIVING("Driving", true, TrackGroup.VEHICLE),
    /** Never detected (activity recognition only sees IN_VEHICLE) — assigned by hand on the
     *  track page to mark rides where the user was a passenger. */
    TAXI("Taxi", true, TrackGroup.VEHICLE),
    STILL("Stationary", false, TrackGroup.STILL),
    UNKNOWN("Moving", true, TrackGroup.UNKNOWN);

    /** Whether [other] belongs in the same track as this activity when detection switches between them. */
    fun sharesTrackWith(other: ActivityType): Boolean = trackGroup == other.trackGroup

    companion object {
        /** The activity transition types we ask Google Play Services to report. */
        val TRACKED_DETECTED_ACTIVITIES = intArrayOf(
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.ON_FOOT,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.STILL,
        )

        /** The [ActivityType] for a persisted `activityType` string (an [ActivityType.name]), or null. */
        fun ofName(stored: String): ActivityType? = entries.firstOrNull { it.name == stored }

        /**
         * Display label for a persisted `activityType` string (an [ActivityType.name]), falling
         * back to a title-cased form for legacy values that no longer map to a known activity.
         */
        fun labelFor(stored: String): String =
            ofName(stored)?.label
                ?: stored.lowercase(Locale.US).replaceFirstChar { it.uppercase() }

        fun fromDetectedActivity(type: Int): ActivityType = when (type) {
            DetectedActivity.IN_VEHICLE -> DRIVING
            DetectedActivity.ON_BICYCLE -> CYCLING
            DetectedActivity.RUNNING -> RUNNING
            DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> WALKING
            DetectedActivity.STILL -> STILL
            else -> UNKNOWN
        }
    }
}

/** Coarse motion family used to decide whether an activity switch splits the track. */
enum class TrackGroup { FOOT, BICYCLE, VEHICLE, STILL, UNKNOWN }
