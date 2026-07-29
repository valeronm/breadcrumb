package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * A per-fix series flattened into the shape of the trip it came from — a jittery one plotted
 * straight through spends most of its ink on the jitter, and the shape it exists to show (pulling
 * away, cruising, slowing into a stop) has to be read past it. Which series want this is the
 * caller's to say; this only knows how.
 *
 * Averaged over a window of **real time**, not of point count: a track's fix spacing varies with
 * the sampling settings, with how long the receiver took to answer, and with a stop's cadence, so a
 * fixed count of neighbours would smooth a dense stretch far harder than a sparse one and neither
 * predictably. The window is short beside anything worth seeing — a stop lasting less than it is
 * not a stop the graph was showing anyway.
 */
object MetricSmoother {

    /** Total width of the averaging window, centred on each point. */
    const val WINDOW_MS = 15_000L

    /**
     * [values] averaged over [windowMs] of surrounding time, index for index with [points].
     *
     * A null value is not a small one — it is a fix that never carried this metric — so nulls come
     * back null and are never averaged in. Averaging also stops at the boundaries the drawn line
     * already breaks at: a missing value, and a segment start, which is a gap in the recording
     * where the far side may be minutes and a mile away. Every run of consecutive values is
     * therefore smoothed on its own, and a run's edges pull only from within it.
     */
    fun timeAveraged(
        points: List<TrackPoint>,
        values: List<Float?>,
        windowMs: Long = WINDOW_MS,
    ): List<Float?> {
        val out = arrayOfNulls<Float>(values.size)

        /**
         * One run averaged in a single pass: both window edges only ever move forward, so the sum
         * between them is carried rather than recomputed, and a track of any length costs one walk
         * rather than one per point. A run holds no nulls by construction, so every value in the
         * window is a real one.
         */
        fun averageRun(from: Int, to: Int) {
            val half = windowMs / 2
            var lo = from
            var hi = from
            var sum = (values[from] ?: 0f).toDouble()
            for (i in from..to) {
                val t = points[i].timestamp
                while (points[lo].timestamp < t - half) {
                    sum -= values[lo] ?: 0f
                    lo++
                }
                while (hi + 1 <= to && points[hi + 1].timestamp <= t + half) {
                    hi++
                    sum += values[hi] ?: 0f
                }
                out[i] = (sum / (hi - lo + 1)).toFloat()
            }
        }

        var runStart = 0
        while (runStart < values.size) {
            if (values[runStart] == null) {
                runStart++
                continue
            }
            var runEnd = runStart
            while (runEnd + 1 < values.size &&
                values[runEnd + 1] != null &&
                !points[runEnd + 1].segmentStart
            ) {
                runEnd++
            }
            averageRun(runStart, runEnd)
            runStart = runEnd + 1
        }
        return out.asList()
    }
}
