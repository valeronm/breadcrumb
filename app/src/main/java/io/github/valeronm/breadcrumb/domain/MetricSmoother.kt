package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import kotlin.math.abs
import kotlin.math.exp

/**
 * A per-fix series flattened into the shape of the trip it came from — a jittery one plotted
 * straight through spends most of its ink on the jitter, and the shape it exists to show (pulling
 * away, cruising, slowing into a stop) has to be read past it. Which series want this is the
 * caller's to say; this only knows how.
 *
 * A Gaussian over **real time**, not point count: a track's fix spacing varies with the sampling
 * settings, with how long the receiver took to answer, and with a stop's cadence, so a fixed count
 * of neighbors would smooth a dense stretch far harder than a sparse one and neither predictably.
 */
object MetricSmoother {

    private const val SIGMA_MS = 15_000L

    /** Beyond this many sigmas a weight is under 1.2% of the center's. */
    private const val REACH_SIGMAS = 3

    /**
     * Every fix weighs each neighbor within reach, so a weight is read from a table at this step
     * rather than computed per pair.
     */
    private const val TABLE_STEP_MS = 100L

    /**
     * [values] averaged with a Gaussian weight of standard deviation [SIGMA_MS], index for index
     * with [points].
     *
     * A null value is not a small one — it is a fix that never carried this metric — so nulls come
     * back null and are never averaged in. Averaging also stops at the boundaries the drawn line
     * breaks at: a missing value, and an index [breaksBefore] marks. Near one, a fix is averaged
     * over the neighbors its own side has.
     */
    fun timeAveraged(
        points: List<TrackPoint>,
        values: List<Float?>,
        breaksBefore: BooleanArray,
    ): List<Float?> {
        val out = arrayOfNulls<Float>(values.size)
        val reachMs = REACH_SIGMAS * SIGMA_MS
        val weights = DoubleArray((reachMs / TABLE_STEP_MS + 1).toInt()) {
            val x = it * TABLE_STEP_MS.toDouble() / SIGMA_MS
            exp(-x * x / 2)
        }

        // Copied out unboxed: the inner loop reads each fix once per neighbor.
        fun smoothRun(from: Int, to: Int) {
            val times = LongArray(to - from + 1) { points[from + it].timestamp }
            val run = DoubleArray(times.size) { checkNotNull(values[from + it]).toDouble() }
            var lo = 0
            var hi = 0
            for (i in times.indices) {
                val t = times[i]
                while (times[lo] < t - reachMs) lo++
                while (hi + 1 < times.size && times[hi + 1] <= t + reachMs) hi++
                var sum = 0.0
                var weightSum = 0.0
                for (j in lo..hi) {
                    val w = weights[(abs(times[j] - t) / TABLE_STEP_MS).toInt()]
                    sum += w * run[j]
                    weightSum += w
                }
                out[from + i] = (sum / weightSum).toFloat()
            }
        }

        var runStart = 0
        while (runStart < values.size) {
            if (values[runStart] == null) {
                runStart++
                continue
            }
            var runEnd = runStart
            while (runEnd + 1 < values.size && values[runEnd + 1] != null && !breaksBefore[runEnd + 1]) {
                runEnd++
            }
            smoothRun(runStart, runEnd)
            runStart = runEnd + 1
        }
        return out.asList()
    }
}
