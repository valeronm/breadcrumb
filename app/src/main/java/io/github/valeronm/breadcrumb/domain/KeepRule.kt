package io.github.valeronm.breadcrumb.domain

/**
 * Whether a finished track is worth keeping. Pure and Android-free so the keep/delete decision is
 * host-testable; the repository loads the points and settings and calls [verdict].
 */
object KeepRule {

    /**
     * Tracks with this many points or fewer, good and ignored counted together, are hard-deleted
     * ([Verdict.PURGE]): nothing to draw and nothing to review, so soft-keeping them only clutters
     * Recently deleted. Ignored points count because a track full of rejected fixes is still
     * reviewable evidence (the track map marks them).
     */
    const val PURGE_MAX_POINTS = 2

    /**
     * Good points needed before a track is a line with any length at all. Equal to
     * [PURGE_MAX_POINTS] by coincidence, not by rule — that floor counts good and ignored fixes
     * together, this one only counts good ones.
     */
    const val MIN_LINE_POINTS = 2

    enum class Verdict {
        /** Clears every bar — close it normally. */
        KEEP,

        /** Below a keep threshold — soft-delete to Recently deleted (restorable). */
        DISCARD,

        /** Empty of information — hard-delete, nothing to review. */
        PURGE,
    }

    /** The user-configured minimums a track must clear to be kept. */
    data class Thresholds(
        val minDurationSec: Int,
        val minLengthM: Int,
        val minExtentM: Int,
    )

    /**
     * A track of [PURGE_MAX_POINTS] or fewer points in total ([pointCount] good + [ignoredCount]
     * rejected) is purged; past that floor, it is kept only if it clears every bar: at least
     * [MIN_LINE_POINTS] good points (a hard floor — one point is never a line with any length),
     * [Thresholds.minDurationSec] seconds, [Thresholds.minLengthM] meters, and — when the extent
     * gate is enabled ([Thresholds.minExtentM] > 0) — an [extent] at least that wide, guarding
     * against a stationary "walk" whose accumulated length is only GPS jitter. [extent] is a lazy
     * supplier so the (potentially O(n)) bounding-box pass runs only when that gate is enabled.
     */
    fun verdict(
        pointCount: Int,
        ignoredCount: Int,
        durationSec: Long,
        distanceMeters: Double,
        thresholds: Thresholds,
        extent: () -> Double,
    ): Verdict {
        if (pointCount + ignoredCount <= PURGE_MAX_POINTS) return Verdict.PURGE
        if (pointCount < MIN_LINE_POINTS) return Verdict.DISCARD
        if (durationSec < thresholds.minDurationSec) return Verdict.DISCARD
        if (distanceMeters < thresholds.minLengthM) return Verdict.DISCARD
        if (thresholds.minExtentM > 0 && extent() < thresholds.minExtentM) return Verdict.DISCARD
        return Verdict.KEEP
    }
}
