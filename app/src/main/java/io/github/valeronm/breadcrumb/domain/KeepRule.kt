package io.github.valeronm.breadcrumb.domain

/**
 * Whether a finished track is worth keeping. Pure and Android-free so the keep/delete decision is
 * host-testable; the repository loads the points and settings and calls [shouldKeep].
 */
object KeepRule {

    /** The user-configured minimums a track must clear to be kept. */
    data class Thresholds(
        val minDurationSec: Int,
        val minLengthM: Int,
        val minExtentM: Int,
    )

    /**
     * A track is kept only if it clears every bar:
     *  - at least two points (a hard floor — one point is never a line with any length),
     *  - runs at least [Thresholds.minDurationSec] seconds,
     *  - is at least [Thresholds.minLengthM] metres long,
     *  - and, when the extent gate is enabled ([Thresholds.minExtentM] > 0), spread at least that
     *    far ([extent]) — guarding against a stationary "walk" whose accumulated length is only GPS
     *    jitter.
     *
     * [extent] is a lazy supplier so the (potentially O(n)) bounding-box pass runs only when the
     * extent gate is actually enabled.
     */
    fun shouldKeep(
        pointCount: Int,
        durationSec: Long,
        distanceMeters: Double,
        thresholds: Thresholds,
        extent: () -> Double,
    ): Boolean {
        if (pointCount < 2) return false
        if (durationSec < thresholds.minDurationSec) return false
        if (distanceMeters < thresholds.minLengthM) return false
        if (thresholds.minExtentM > 0 && extent() < thresholds.minExtentM) return false
        return true
    }
}
