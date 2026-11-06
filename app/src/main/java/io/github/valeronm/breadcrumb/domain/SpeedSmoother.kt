package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import kotlin.math.max

/**
 * A track's speed as its graph draws it. A receiver's own speed is close to exact, so what crowds a whole trip's
 * graph is the speed's real second-to-second changes, and a stop is what an average over neighbors erases.
 */
object SpeedSmoother {

    /** Each reading counts as no finer than this, so the line follows the trip's stops and pulls rather than every second. */
    private const val ERROR_FLOOR_MPS = 1.0

    /** How readily the speed's rate of change itself changes, as the spectral density of jerk in m²/s⁵. */
    private const val JERK_NOISE = 0.01

    /** The variance of a run's acceleration before its readings say otherwise, in (m/s²)². */
    private const val ACCELERATION_PRIOR = 4.0

    /** The variance of a run's first speed where its first fix measured none, in m²/s². */
    private const val SPEED_PRIOR = 100.0

    /**
     * [kmh] as the graph draws it, index for index with [points]: each run passes through a constant-acceleration
     * Kalman smoother over real time, [errorKmh] the standard deviation of each reading. A NaN error, a reading with
     * no accuracy, counts as the [ERROR_FLOOR_MPS]; an infinite one marks a fix that measured nothing, which the line
     * passes over. No speed is drawn below zero, and smoothing never crosses a run's end, an index [breaksBefore]
     * marks.
     */
    fun smoothedKmh(points: List<TrackPoint>, kmh: FloatArray, errorKmh: FloatArray, breaksBefore: BooleanArray): FloatArray {
        val out = FloatArray(kmh.size)
        var first = 0
        for (i in 1..kmh.size) {
            if (i == kmh.size || breaksBefore[i]) {
                smoothRun(points, kmh, errorKmh, first until i, out)
                first = i
            }
        }
        return out
    }

    private fun varianceOf(errorKmh: Float): Double = when {
        errorKmh.isNaN() -> ERROR_FLOOR_MPS * ERROR_FLOOR_MPS
        errorKmh.isInfinite() -> Double.POSITIVE_INFINITY
        else -> max(errorKmh / Speed.KMH_PER_MPS, ERROR_FLOOR_MPS).let { it * it }
    }

    /** One run's speeds through [RateSmoother], in m/s. */
    private fun smoothRun(points: List<TrackPoint>, kmh: FloatArray, errorKmh: FloatArray, run: IntRange, out: FloatArray) {
        val variance = DoubleArray(run.last - run.first + 1) { varianceOf(errorKmh[run.first + it]) }
        val speed = DoubleArray(variance.size) { kmh[run.first + it] / Speed.KMH_PER_MPS }
        if (variance[0].isInfinite()) {
            variance[0] = SPEED_PRIOR
            speed[0] = 0.0
        }
        RateSmoother.smooth(points.subList(run.first, run.last + 1), variance, JERK_NOISE, ACCELERATION_PRIOR, speed)
        for (k in speed.indices) out[run.first + k] = (max(speed[k], 0.0) * Speed.KMH_PER_MPS).toFloat()
    }
}
