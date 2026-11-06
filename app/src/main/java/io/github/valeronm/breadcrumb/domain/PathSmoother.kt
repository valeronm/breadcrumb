package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import kotlin.math.IEEErem
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/** Where a track's line is drawn through its fixes: GPS scatters a track a few meters to either side of its path. */
object PathSmoother {

    /** A reported accuracy finer than this would let one fix outweigh every neighbor. */
    private const val ACCURACY_FLOOR_M = 1.0

    /** The variance of a run's speed before its fixes say otherwise, in m²/s²: loose enough for any carrier. */
    private const val SPEED_PRIOR_M2_S2 = 100.0

    private const val METERS_PER_DEGREE = 111_320.0
    private const val FULL_TURN_DEG = 360.0

    /** False for an [unmeasured] track, whose spread and restamped times don't reflect how it moved. */
    fun moves(unmeasured: Boolean): Boolean = !unmeasured

    /**
     * How readily a path is believed to turn, as the spectral density of its acceleration in m²/s³. A lower value
     * smooths more and cuts the corners its carrier takes. A vehicle's value cuts no carrier's corners, which is why a
     * carrier the app can't name gets it.
     */
    private fun turnNoise(activity: ActivityType?): Double = when (activity?.trackGroup) {
        TrackGroup.FOOT -> 0.05
        TrackGroup.BICYCLE -> 1.0
        else -> 8.0
    }

    /**
     * [points]' positions as the line draws them, longitude and latitude interleaved; the fixes as recorded unless
     * [smoothed] and the track [moves]. Smoothed, each run passes through a constant-velocity Kalman smoother over
     * real time, a fix's accuracy its measurement noise and [activity] how readily the path turns, and each fix moves
     * no further than its accuracy, a fix with none counting as [FixAccuracy.ASSUMED_M]. The first and last fix of each
     * run ([SegmentBreaks.split]) stay where they were recorded, and smoothing never crosses a run's end.
     */
    fun lonLat(points: List<TrackPoint>, activity: ActivityType?, unmeasured: Boolean, smoothed: Boolean): DoubleArray {
        val out = DoubleArray(points.size * 2)
        for (i in points.indices) {
            out[2 * i] = points[i].longitude
            out[2 * i + 1] = points[i].latitude
        }
        if (!smoothed || !moves(unmeasured)) return out
        val q = turnNoise(activity)
        var first = 0
        for (run in SegmentBreaks.split(points)) {
            smoothRun(points, first, first + run.size - 1, q, out)
            first += run.size
        }
        return out
    }

    private fun metersPerDegreeLon(latitude: Double): Double = METERS_PER_DEGREE * cos(Math.toRadians(latitude))

    private fun varianceOf(fix: TrackPoint): Double = max(FixAccuracy.metersOf(fix), ACCURACY_FLOOR_M).let { it * it }

    /**
     * The run from [first] to [last] through [RateSmoother] on a plane in meters local to its first fix, both axes in
     * one pass since a fix's noise and the turn noise are the same in every direction.
     */
    private fun smoothRun(points: List<TrackPoint>, first: Int, last: Int, q: Double, out: DoubleArray) {
        val n = last - first + 1
        if (n < 3) return
        val lat0 = points[first].latitude
        // East is summed fix to fix, each step wrapped and scaled at its own latitude, so a run across the
        // antimeridian stays continuous and a long one north or south keeps its meters true.
        val x = DoubleArray(n)
        val y = DoubleArray(n) { (points[first + it].latitude - lat0) * METERS_PER_DEGREE }
        for (k in 1 until n) {
            val a = points[first + k - 1]
            val b = points[first + k]
            val step = (b.longitude - a.longitude).IEEErem(FULL_TURN_DEG)
            x[k] = x[k - 1] + step * metersPerDegreeLon((a.latitude + b.latitude) / 2)
        }
        val run = points.subList(first, last + 1)
        val sx = x.copyOf()
        val sy = y.copyOf()
        RateSmoother.smooth(run, DoubleArray(n) { varianceOf(run[it]) }, q, SPEED_PRIOR_M2_S2, sx, sy)
        for (k in 1 until n - 1) {
            val fix = points[first + k]
            val dx = sx[k] - x[k]
            val dy = sy[k] - y[k]
            val accuracy = FixAccuracy.metersOf(fix)
            val moved = hypot(dx, dy)
            val scale = if (moved > accuracy) accuracy / moved else 1.0
            out[2 * (first + k)] = (fix.longitude + dx * scale / metersPerDegreeLon(fix.latitude)).IEEErem(FULL_TURN_DEG)
            out[2 * (first + k) + 1] = fix.latitude + dy * scale / METERS_PER_DEGREE
        }
    }
}
