package io.github.valeronm.breadcrumb.domain

/**
 * Reduces one GNSS status callback to the two numbers stored per fix: satellites used, and the mean
 * C/N0 of the strongest [topN] (quality metadata; the no-GNSS cross-check's input) — an accumulator,
 * not a function, so one reused instance keeps the ~1/s pass of a whole recording allocation-free.
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
         * [lastGnssElapsedMs] (both elapsed-realtime millis); 0 fails open — a never-locked
         * session keeps recording rather than emptying the track. Once locked, a fix more
         * than [maxAgeMs] past the last satellite fix is a network/dead-reckoning fabrication.
         */
        fun backed(lastGnssElapsedMs: Long, fixElapsedMs: Long, maxAgeMs: Long): Boolean {
            if (lastGnssElapsedMs == 0L) return true
            return fixElapsedMs - lastGnssElapsedMs <= maxAgeMs
        }
    }
}
