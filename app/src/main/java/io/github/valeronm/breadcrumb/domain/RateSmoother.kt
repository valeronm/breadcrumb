package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * A forward Kalman pass and a Rauch–Tung–Striebel pass back over one run of fixes, for a value that moves at a rate
 * whose own change is white noise. Every channel shares one covariance, since each is measured with the same
 * variance and believed to change as readily.
 */
object RateSmoother {

    /**
     * Smooths each of [values], index for index with [run], in place. [variance] is each measurement's, infinite for
     * a fix that measured nothing, except the first entry, which seeds the filter and must be finite. [rateNoise] is the
     * spectral density of the rate's change, and [rateVariance] the rate's variance before any measurement.
     */
    fun smooth(run: List<TrackPoint>, variance: DoubleArray, rateNoise: Double, rateVariance: Double, vararg values: DoubleArray) {
        val n = run.size
        if (n < 2) return
        val dt = DoubleArray(n) { if (it == 0) 0.0 else (run[it].timestamp - run[it - 1].timestamp) / 1000.0 }
        val valueGain = DoubleArray(n)
        val rateGain = DoubleArray(n)
        // The backward pass's gain from each fix to the next, a 2×2 matrix row-major.
        val backGain = DoubleArray(4 * n)
        var p00 = variance[0]
        var p01 = 0.0
        var p11 = rateVariance
        for (k in 1 until n) {
            val t = dt[k]
            val pp00 = p00 + 2 * t * p01 + t * t * p11 + rateNoise * t * t * t / 3
            val pp01 = p01 + t * p11 + rateNoise * t * t / 2
            val pp11 = p11 + rateNoise * t
            val det = pp00 * pp11 - pp01 * pp01
            val a00 = p00 + t * p01
            val a10 = p01 + t * p11
            val g = 4 * (k - 1)
            backGain[g] = (a00 * pp11 - p01 * pp01) / det
            backGain[g + 1] = (p01 * pp00 - a00 * pp01) / det
            backGain[g + 2] = (a10 * pp11 - p11 * pp01) / det
            backGain[g + 3] = (p11 * pp00 - a10 * pp01) / det
            // An infinite variance gives no gain, which carries the prediction and its covariance through.
            val s = pp00 + variance[k]
            valueGain[k] = pp00 / s
            rateGain[k] = pp01 / s
            p00 = (1 - valueGain[k]) * pp00
            p01 = (1 - valueGain[k]) * pp01
            p11 = pp11 - rateGain[k] * pp01
        }
        for (value in values) {
            val rate = DoubleArray(n)
            for (k in 1 until n) {
                val predicted = value[k - 1] + dt[k] * rate[k - 1]
                val innovation = value[k] - predicted
                value[k] = predicted + valueGain[k] * innovation
                rate[k] = rate[k - 1] + rateGain[k] * innovation
            }
            for (k in n - 2 downTo 0) {
                val g = 4 * k
                val dv = value[k + 1] - (value[k] + dt[k + 1] * rate[k])
                val dr = rate[k + 1] - rate[k]
                value[k] += backGain[g] * dv + backGain[g + 1] * dr
                rate[k] += backGain[g + 2] * dv + backGain[g + 3] * dr
            }
        }
    }
}
