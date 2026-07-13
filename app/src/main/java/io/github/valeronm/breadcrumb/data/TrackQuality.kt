package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * Pure track geometry and fix-quality math over recorded [TrackPoint]s — distance, bounding extent,
 * per-point speed, and the "bad fix" rule — all host-testable via an injectable [DistanceFn].
 *
 * The bad-fix rule ([TrackQuality.badFixReason]) decides which fixes are unreliable so they can be flagged and
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
        ActivityType.DRIVING, ActivityType.TAXI, ActivityType.UNKNOWN -> 220.0
    }

    fun distanceMeters(a: TrackPoint, b: TrackPoint, distance: DistanceFn = AndroidDistance): Double =
        distance.metres(a.latitude, a.longitude, b.latitude, b.longitude)

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

    /** First seam at least this fast (km/h) to be a candidate stray — an implausible launch from
     *  a drive start (40 km/h in a ~1 s opening seam is ~1 g of acceleration from standstill). */
    private const val LEADING_STRAY_MIN_KMH = 40.0

    /** …and at least this many times the following real pace, so genuine fast starts (first seam
     *  ≈ the rest of the track) aren't flagged — only the fast-first-then-slow stray shape is. */
    private const val LEADING_STRAY_FACTOR = 4.0

    /** How many seams after the first to sample for the track's real early pace. */
    private const val LEADING_STRAY_LOOKAHEAD = 4

    /** How many leading points [leadingPointIsJump] inspects — a prefix this long decides it. */
    const val LEADING_CHECK_POINT_COUNT = LEADING_STRAY_LOOKAHEAD + 1

    /** Speed (km/h) across a seam, or null when its time gap is non-positive (can't derive). */
    private fun seamSpeedKmh(a: TrackPoint, b: TrackPoint, distance: DistanceFn): Double? {
        val dtSec = (b.timestamp - a.timestamp) / 1000.0
        if (dtSec <= 0) return null
        return distanceMeters(a, b, distance) / dtSec * 3.6
    }

    /**
     * Whether the track opens with a stray point: the first seam's speed is implausibly high for a
     * drive *start* — well above [LEADING_STRAY_MIN_KMH] and at least [LEADING_STRAY_FACTOR]× the
     * real pace of the seams that follow (a car pulling out does a few km/h, not 180). This is the
     * classic recorder cold-start artifact — the first fix lands far off, then the track is
     * consistent — common in imported GPX, which bypasses live ingest filtering.
     *
     * An absolute jump ceiling misses it: a stray commonly reads 40–200 km/h, under the driving
     * ceiling yet impossible one second after setting off. The comparison is relative to the
     * track's own following pace, so it catches the sub-ceiling cases the forward jump check can't.
     * Repair: ignore the first point.
     */
    fun leadingPointIsJump(
        points: List<TrackPoint>,
        distance: DistanceFn = AndroidDistance,
    ): Boolean {
        if (points.size < 3) return false
        val firstSeam = seamSpeedKmh(points[0], points[1], distance) ?: return false
        if (firstSeam < LEADING_STRAY_MIN_KMH) return false
        val followPace = (2..LEADING_STRAY_LOOKAHEAD.coerceAtMost(points.size - 1))
            .mapNotNull { seamSpeedKmh(points[it - 1], points[it], distance) }
            .maxOrNull() ?: return false
        return firstSeam >= LEADING_STRAY_FACTOR * followPace
    }

    /**
     * Whether [point] is a bad fix relative to the last accepted ("good") point, and why —
     * [IgnoreReason.ACCURACY] or [IgnoreReason.JUMP] — or null for a good fix. A fix is bad if its
     * accuracy radius is at least [maxAccuracyM], or if reaching it from [lastGood] would require an
     * implausible speed for [activity] (a GPS teleport — these can have good reported accuracy, so
     * the speed check is independent of the accuracy gate). [lastGood] is null for the first point
     * of a track (or a segment). [distance] is injectable so the speed logic is host-testable.
     */
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
