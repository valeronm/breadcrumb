package io.github.valeronm.breadcrumb.data

import android.location.Location
import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * Decides which recorded fixes are unreliable ("bad fixes") so they can be flagged and excluded
 * from distance, the rendered track line, and exports. The fixes are still stored — the count of
 * ignored points is itself a useful signal that a track is questionable.
 *
 * This is the single source of truth for the rule, shared by live recording
 * ([io.github.valeronm.breadcrumb.location.LocationRecordingService]) and the one-time backfill
 * over existing tracks ([TrackRepository.reprocessAllTracks]).
 */
object TrackQuality {

    /** A position delta below this (metres) over a zero/negative time gap isn't a real jump. */
    private const val MIN_JUMP_M = 10.0

    /** Plausible upper-bound ground speed (km/h) per activity, used to reject teleport fixes. */
    private fun maxSpeedKmh(activity: ActivityType): Double = when (activity) {
        ActivityType.WALKING, ActivityType.STILL -> 12.0
        ActivityType.RUNNING -> 30.0
        ActivityType.CYCLING -> 70.0
        ActivityType.DRIVING, ActivityType.UNKNOWN -> 220.0
    }

    fun distanceMeters(a: TrackPoint, b: TrackPoint): Double {
        val result = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
        return result[0].toDouble()
    }

    /**
     * Whether [point] is a bad fix relative to the last accepted ("good") point. A fix is bad if its
     * accuracy radius is at least [maxAccuracyM], or if reaching it from [lastGood] would require an
     * implausible speed for [activity] (a GPS teleport — these can have good reported accuracy, so
     * the speed check is independent of the accuracy gate). [lastGood] is null for the first point
     * of a track (or a segment).
     */
    fun isBadFix(lastGood: TrackPoint?, point: TrackPoint, activity: ActivityType, maxAccuracyM: Float): Boolean {
        val accuracy = point.accuracy
        if (accuracy != null && accuracy >= maxAccuracyM) return true
        if (lastGood == null) return false
        val distance = distanceMeters(lastGood, point)
        val dtSec = (point.timestamp - lastGood.timestamp) / 1000.0
        val speedKmh = when {
            dtSec > 0 -> distance / dtSec * 3.6
            distance > MIN_JUMP_M -> Double.MAX_VALUE
            else -> 0.0
        }
        return speedKmh > maxSpeedKmh(activity)
    }
}
