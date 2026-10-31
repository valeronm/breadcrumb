package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import kotlin.math.abs

/**
 * The fixes inside a stop that say nothing its first and last fix don't: where it was, when it began
 * and when it ended. A receiver logging once a second through a red light or a parked hour writes the
 * same spot over and over, and its jitter around that spot reads as distance never driven and as
 * speed spikes on the graph. [TrackSettle] flags them [IgnoreReason.STATIONARY]; the two ends stay
 * on the path. Pure and Android-free.
 */
object StationaryRuns {

    /** Every fix of a stop stays within this of its first; GPS drift at a standstill stays under it. */
    const val RADIUS_M = 10.0

    /** With [RADIUS_M], bounds a thinned stop's net speed below [GraphBreaks.STOP_MAX_SPEED]. */
    const val MIN_SPAN_MS = 30_000L

    /**
     * A standstill's altitude wanders within this many times its fixes' vertical accuracy, while a
     * stair climb rises past that inside [MIN_SPAN_MS].
     */
    private const val ALTITUDE_ACCURACY_FACTOR = 2.0

    /** A fix reporting 2 m of vertical accuracy at a standstill still wanders past 4 m. */
    private const val ALTITUDE_FLOOR_M = 5.0

    /** True when either fix lacks an altitude or a vertical accuracy. */
    fun sameLevel(a: TrackPoint, b: TrackPoint): Boolean {
        val altitudeA = a.altitude ?: return true
        val altitudeB = b.altitude ?: return true
        val accuracyA = a.verticalAccuracy ?: return true
        val accuracyB = b.verticalAccuracy ?: return true
        val allowance = maxOf(ALTITUDE_FLOOR_M, ALTITUDE_ACCURACY_FACTOR * maxOf(accuracyA, accuracyB))
        return abs(altitudeB - altitudeA) <= allowance
    }

    /**
     * Indices into [points] — all of a track's points in order, good and ignored alike — of the good
     * fixes strictly inside a stop. A stop is a run of good fixes no segment break interrupts, each
     * within [RADIUS_M] of the run's first and at its level ([sameLevel]), spanning at least
     * [MIN_SPAN_MS]. Ignored fixes neither end a run nor join it, except that a break on one ends the
     * run as it does on a good fix ([SegmentBreaks]).
     */
    fun inner(points: List<TrackPoint>, distance: DistanceFn): Set<Int> {
        val good = points.indices.filter { !points[it].ignored }
        // Index for index with [good]: the same fixes, a break on an ignored one carried onto them.
        val path = SegmentBreaks.goodWithCarriedBreaks(points)
        val inner = HashSet<Int>()
        var a = 0
        while (a < path.size) {
            val anchor = path[a]
            var b = a
            while (b + 1 < path.size && !path[b + 1].segmentStart && inStop(anchor, path[b + 1], distance)) b++
            if (b - a >= 2 && path[b].timestamp - anchor.timestamp >= MIN_SPAN_MS) {
                for (k in a + 1 until b) inner += good[k]
                a = b
            } else {
                a++
            }
        }
        return inner
    }

    private fun inStop(anchor: TrackPoint, p: TrackPoint, distance: DistanceFn): Boolean =
        sameLevel(anchor, p) && distance.meters(anchor.latitude, anchor.longitude, p.latitude, p.longitude) <= RADIUS_M
}
