package com.valeronm.activitytracker.data

import com.google.android.gms.location.DetectedActivity

/**
 * The motion states we care about. Maps Google's [DetectedActivity] constants onto a small set
 * of profiles, each with its own GPS sampling cadence.
 */
enum class ActivityType(
    val label: String,
    /** Target time between location samples while in this activity. */
    val intervalMs: Long,
    /** Smallest displacement (metres) before we accept a new sample. */
    val minDistanceM: Float,
    /** Whether we actively record GPS while in this state. */
    val recording: Boolean,
) {
    WALKING("Walking", 6_000, 5f, true),
    RUNNING("Running", 4_000, 5f, true),
    CYCLING("Cycling", 4_000, 5f, true),
    DRIVING("Driving", 3_000, 5f, true),
    STILL("Stationary", 0, 0f, false),
    UNKNOWN("Moving", 6_000, 5f, true);

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
