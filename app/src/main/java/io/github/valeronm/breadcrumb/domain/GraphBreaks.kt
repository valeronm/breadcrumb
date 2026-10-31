package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * Where a per-fix series is neither joined fix to fix nor averaged across: a
 * [TrackPoint.segmentStart], and a stop with no fixes on the path — one [StationaryRuns] thinned,
 * or one the receiver reported nothing through, holding still under a minimum update distance. A
 * line joined across a stop shows it as steady movement. A gap whose ends differ in level
 * ([StationaryRuns.sameLevel]) is not a stop. Pure and Android-free.
 */
object GraphBreaks {

    /** GPS drift over a stop stays well under this. */
    val STOP_MAX_SPEED: Speed = Speed.mps(0.5)

    /** Every stop [StationaryRuns] thins leaves a gap at least this long. */
    const val STOP_MIN_GAP_MS = StationaryRuns.MIN_SPAN_MS

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
                StationaryRuns.sameLevel(points[i - 1], points[i])
        }
        return breaks
    }

    /** Median time between consecutive fixes within a recorded stretch; 0 when there is none. */
    private fun medianSpacingMs(points: List<TrackPoint>): Long {
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
