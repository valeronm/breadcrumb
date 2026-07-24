package io.github.valeronm.breadcrumb.location

import com.google.android.gms.location.DetectedActivity
import io.github.valeronm.breadcrumb.domain.ActivityType

/** The activity transition types we ask Google Play Services to report. */
internal val TRACKED_DETECTED_ACTIVITIES = intArrayOf(
    DetectedActivity.IN_VEHICLE,
    DetectedActivity.ON_BICYCLE,
    DetectedActivity.ON_FOOT,
    DetectedActivity.WALKING,
    DetectedActivity.RUNNING,
    DetectedActivity.STILL,
)

/** The [ActivityType] a GMS [DetectedActivity] constant reduces to. */
internal fun activityTypeOfDetected(type: Int): ActivityType = when (type) {
    DetectedActivity.IN_VEHICLE -> ActivityType.DRIVING
    DetectedActivity.ON_BICYCLE -> ActivityType.CYCLING
    DetectedActivity.RUNNING -> ActivityType.RUNNING
    DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> ActivityType.WALKING
    DetectedActivity.STILL -> ActivityType.STILL
    else -> ActivityType.UNKNOWN
}
