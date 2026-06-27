package io.github.valeronm.breadcrumb.data

import com.google.android.gms.location.DetectedActivity

/**
 * The motion states we care about. Maps Google's [DetectedActivity] constants onto a small set
 * of profiles, each with its own GPS sampling cadence.
 */
enum class ActivityType(
    val label: String,
    /** Whether we actively record GPS while in this state. */
    val recording: Boolean,
) {
    WALKING("Walking", true),
    RUNNING("Running", true),
    CYCLING("Cycling", true),
    DRIVING("Driving", true),
    STILL("Stationary", false),
    UNKNOWN("Moving", true);

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
