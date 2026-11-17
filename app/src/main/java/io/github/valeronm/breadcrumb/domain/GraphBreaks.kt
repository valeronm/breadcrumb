package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import kotlin.math.abs

/**
 * Where a per-fix series is neither joined fix to fix nor averaged across: a
 * [TrackPoint.segmentStart], and a stop the receiver reported nothing through, holding still under
 * a minimum update distance. A line joined across a stop shows it as steady movement. A gap whose
 * ends differ in level ([sameLevel]) is not a stop. Pure and Android-free.
 */
object GraphBreaks {

    /** GPS drift over a stop stays well under this. */
    val STOP_MAX_SPEED: Speed = Speed.mps(0.5)

    /** The shortest silence the graphs break at as a stop. */
    const val STOP_MIN_GAP_MS = 30_000L

    /**
     * A gap must also exceed the track's usual fix spacing by this factor, since a track sampled
     * every 30 s or more records its stops as fixes that far apart.
     */
    const val SPACING_FACTOR = 3

    /**
     * Index for index with [points]: true where nothing joins `points[i - 1]` to `points[i]`.
     * [metersBefore] is the distance from each fix's predecessor, 0 at index 0
     * (`TrackQuality.Seams.meters`).
     */
    fun of(points: List<TrackPoint>, metersBefore: DoubleArray): BooleanArray {
        val breaks = BooleanArray(points.size) { points[it].segmentStart }
        val minGapMs = maxOf(STOP_MIN_GAP_MS, SPACING_FACTOR * medianSpacingMs(points))
        for (i in 1 until points.size) {
            if (breaks[i]) continue
            val gapMs = points[i].timestamp - points[i - 1].timestamp
            breaks[i] = gapMs >= minGapMs &&
                Speed.mps(metersBefore[i] * 1000.0 / gapMs) < STOP_MAX_SPEED &&
                sameLevel(points[i - 1], points[i])
        }
        return breaks
    }

    /**
     * A standstill's altitude wanders within this many times its fixes' vertical accuracy, while a
     * stair climb rises past that inside [STOP_MIN_GAP_MS].
     */
    private const val ALTITUDE_ACCURACY_FACTOR = 2.0

    /** A fix reporting 2 m of vertical accuracy at a standstill still wanders past 4 m. */
    private const val ALTITUDE_FLOOR_M = 5.0

    /** True when either fix lacks an altitude or a vertical accuracy. */
    internal fun sameLevel(a: TrackPoint, b: TrackPoint): Boolean {
        val altitudeA = a.altitude ?: return true
        val altitudeB = b.altitude ?: return true
        val accuracyA = a.verticalAccuracy ?: return true
        val accuracyB = b.verticalAccuracy ?: return true
        val allowance = maxOf(ALTITUDE_FLOOR_M, ALTITUDE_ACCURACY_FACTOR * maxOf(accuracyA, accuracyB))
        return abs(altitudeB - altitudeA) <= allowance
    }

    /** Median time between consecutive fixes within a recorded stretch; 0 when there is none. */
    internal fun medianSpacingMs(points: List<TrackPoint>): Long {
        val gaps = LongArray(points.size)
        var n = 0
        for (i in 1 until points.size) {
            val gap = points[i].timestamp - points[i - 1].timestamp
            if (gap > 0 && !points[i].segmentStart) gaps[n++] = gap
        }
        if (n == 0) return 0
        gaps.sort(0, n)
        return gaps[n / 2]
    }
}
