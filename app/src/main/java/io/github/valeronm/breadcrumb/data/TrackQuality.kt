package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * Pure track geometry and fix-quality math over recorded [TrackPoint]s — distance, bounding extent,
 * per-point speed, and the "bad fix" rule — all host-testable via an injectable [DistanceFn].
 *
 * The bad-fix rule ([isBadFix]) decides which fixes are unreliable so they can be flagged and
 * excluded from distance, the rendered track line, and exports; the fixes are still stored, since
 * the count of ignored points is itself a signal that a track is questionable. It's the single
 * source of truth for that rule, applied by live recording
 * ([io.github.valeronm.breadcrumb.location.LocationRecordingService]) as each fix is ingested.
 */
/**
 * Why a fix was flagged [TrackPoint.ignored]. [code] is the stable string stored in the DB
 * (and null for points recorded before reasons were tracked).
 */
enum class IgnoreReason(val code: String) {
    /** Accuracy radius at or beyond the configured gate. */
    ACCURACY("accuracy"),
    /** Reaching the fix from the last good point would need an implausible speed (GPS teleport). */
    JUMP("jump"),
    /** No recent satellite fix backing it (provider fabrication — tunnel dead-reckoning etc.). */
    NO_GNSS("no_gnss");

    companion object {
        fun fromCode(code: String?): IgnoreReason? = entries.firstOrNull { it.code == code }
    }
}

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

    fun distanceMeters(a: TrackPoint, b: TrackPoint, distance: DistanceFn = AndroidDistance): Double =
        distance.metres(a.latitude, a.longitude, b.latitude, b.longitude)

    /**
     * How far a track spread through space: the diagonal of its lat/lon bounding box, in metres.
     * Distinguishes a real trip from a stationary blob — unlike accumulated length, GPS jitter can't
     * inflate it, since standing still keeps every fix inside a small box. 0 for fewer than 2 points.
     */
    fun boundingExtentMeters(points: List<TrackPoint>, distance: DistanceFn = AndroidDistance): Double {
        if (points.size < 2) return 0.0
        var minLat = points[0].latitude; var maxLat = minLat
        var minLon = points[0].longitude; var maxLon = minLon
        for (p in points) {
            if (p.latitude < minLat) minLat = p.latitude
            if (p.latitude > maxLat) maxLat = p.latitude
            if (p.longitude < minLon) minLon = p.longitude
            if (p.longitude > maxLon) maxLon = p.longitude
        }
        return distance.metres(minLat, minLon, maxLat, maxLon)
    }

    /**
     * Per-point speed in km/h: the GPS-reported speed where present (non-null and non-negative),
     * else derived from the previous point over [distance], else 0. [distance] is injectable so the
     * derivation is host-testable. Used to colour the rendered track by speed.
     */
    fun pointSpeedsKmh(points: List<TrackPoint>, distance: DistanceFn = AndroidDistance): FloatArray {
        val out = FloatArray(points.size)
        var prev: TrackPoint? = null
        for (i in points.indices) {
            val p = points[i]
            val reported = p.speed
            out[i] = when {
                reported != null && reported >= 0f -> reported * 3.6f
                prev != null -> {
                    val dtSec = (p.timestamp - prev.timestamp) / 1000.0
                    if (dtSec > 0) (distanceMeters(prev, p, distance) / dtSec * 3.6).toFloat() else 0f
                }
                else -> 0f
            }
            prev = p
        }
        return out
    }

    /**
     * Whether [point] is a bad fix relative to the last accepted ("good") point. A fix is bad if its
     * accuracy radius is at least [maxAccuracyM], or if reaching it from [lastGood] would require an
     * implausible speed for [activity] (a GPS teleport — these can have good reported accuracy, so
     * the speed check is independent of the accuracy gate). [lastGood] is null for the first point
     * of a track (or a segment). [distance] is injectable so the speed logic is host-testable.
     */
    fun isBadFix(
        lastGood: TrackPoint?,
        point: TrackPoint,
        activity: ActivityType,
        maxAccuracyM: Float,
        distance: DistanceFn = AndroidDistance,
    ): Boolean = badFixReason(lastGood, point, activity, maxAccuracyM, distance) != null

    /** Like [isBadFix], but says *why* — [IgnoreReason.ACCURACY] or [IgnoreReason.JUMP] — or null. */
    fun badFixReason(
        lastGood: TrackPoint?,
        point: TrackPoint,
        activity: ActivityType,
        maxAccuracyM: Float,
        distance: DistanceFn = AndroidDistance,
    ): IgnoreReason? {
        val accuracy = point.accuracy
        if (accuracy != null && accuracy >= maxAccuracyM) return IgnoreReason.ACCURACY
        if (lastGood == null) return null
        val gapMeters = distanceMeters(lastGood, point, distance)
        val dtSec = (point.timestamp - lastGood.timestamp) / 1000.0
        val speedKmh = when {
            dtSec > 0 -> gapMeters / dtSec * 3.6
            gapMeters > MIN_JUMP_M -> Double.MAX_VALUE
            else -> 0.0
        }
        return if (speedKmh > maxSpeedKmh(activity)) IgnoreReason.JUMP else null
    }
}
