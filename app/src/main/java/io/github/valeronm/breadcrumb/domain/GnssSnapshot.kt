package io.github.valeronm.breadcrumb.domain

/**
 * Reduction of one GNSS status callback to the two numbers the recorder stores per fix: how many
 * satellites the fix used, and the mean C/N0 of the strongest [topN] of them (signal quality
 * metadata, and the input to the no-GNSS cross-check). An accumulator, not a function, so the
 * caller can reuse one instance across callbacks — the status ticks ~1/s for the whole recording,
 * and this pass must stay allocation-free.
 */
class GnssSnapshot(topN: Int = 4) {
    private val top = FloatArray(topN) // strongest C/N0s seen, descending
    private var topCount = 0

    /** Satellites used in the fix so far this pass. */
    var usedInFix = 0
        private set

    fun reset() {
        usedInFix = 0
        topCount = 0
    }

    /** Folds one satellite in. Unreported C/N0 (zero or below) counts toward [usedInFix] but
     *  can't contribute signal strength. */
    fun add(used: Boolean, cn0DbHz: Float) {
        if (!used) return
        usedInFix++
        var cn0 = cn0DbHz
        if (cn0 <= 0f) return
        for (j in 0 until topCount) {
            if (cn0 > top[j]) {
                val t = top[j]
                top[j] = cn0
                cn0 = t
            }
        }
        if (topCount < top.size) top[topCount++] = cn0
    }

    /** Mean C/N0 (dB-Hz) of the strongest satellites, or null when none reported a strength. */
    fun topCn0Mean(): Float? {
        if (topCount == 0) return null
        var sum = 0f
        for (j in 0 until topCount) sum += top[j]
        return sum / topCount
    }

    companion object {
        /**
         * Whether a fix taken at [fixElapsedMs] is backed by a real satellite fix seen at
         * [lastGnssElapsedMs] (both elapsed-realtime millis). Fails open while
         * [lastGnssElapsedMs] is still 0 — a session that never locks must keep recording
         * rather than emptying the track; once locked, a fix more than [maxAgeMs] past the
         * last satellite fix is a network/dead-reckoning fabrication.
         */
        fun backed(lastGnssElapsedMs: Long, fixElapsedMs: Long, maxAgeMs: Long): Boolean {
            if (lastGnssElapsedMs == 0L) return true
            return fixElapsedMs - lastGnssElapsedMs <= maxAgeMs
        }
    }
}
