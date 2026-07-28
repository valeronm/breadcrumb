package io.github.valeronm.breadcrumb.domain

import kotlin.math.abs

/**
 * A coordinate-box test that rules out candidates too far away to matter without paying for a
 * [DistanceFn] call: both axes compare against the candidate's own capture radius, and both bounds
 * *understate* the separation, so a qualifying candidate is never rejected — over-admitting only
 * costs the distance call that would have run anyway. It exists because the two derivation loops
 * asking "which anchor captures this point?" ([PlaceClusterer], and [StayDeriver]'s nearest-pin
 * override) scan every anchor per point over the whole history, while the ellipsoidal distance
 * costs orders of magnitude more than the two subtractions that can rule one out: on a synthetic
 * history a few thousand endpoints long this removes ~98% of the distance calls and the whole
 * clustering with them, and — the loops being quadratic in what the history accumulates — is what
 * keeps a place rename from re-deriving for half a second. **The scale is asked of [DistanceFn],
 * not assumed to be Earth's** — distance is an injected seam: a bound built on hardcoded
 * metres-per-degree would silently reject qualifying candidates under any distance function scaled
 * differently from the ellipsoid (exactly what the tests inject); two probes per point, reused
 * across every candidate, buy consistency with whatever function is in use. The box is not
 * latitude alone: a history strung out east-west along one latitude — a coastal city, a valley —
 * prunes almost nothing on the north-south bound, and everything on both.
 */
class ReachBound private constructor(
    private val lat: Double,
    private val lon: Double,
    private val latMetersPerDegree: Double,
    private val lonMetersPerDegree: Double,
) {

    /**
     * True when the coordinate deltas alone put ([otherLat], [otherLon]) beyond [radiusM] — no
     * distance call can bring it back inside.
     */
    fun outOfReach(otherLat: Double, otherLon: Double, radiusM: Double): Boolean {
        val reach = radiusM * SLACK
        return abs(otherLat - lat) * latMetersPerDegree > reach ||
            abs(otherLon - lon) * lonMetersPerDegree > reach
    }

    companion object {

        /** Probe span in degrees (~100 m): short enough to read as local, long enough that the
         *  distance function's own rounding doesn't dominate the quotient. */
        private const val PROBE_DEGREES = 0.001

        /**
         * How far past the radius the box still admits. The probed scale is one point's, not a
         * bound over the box: longitude shortens toward the pole, so a poleward candidate sits at
         * a slightly smaller scale than probed — ~0.014% at 60° latitude, 500 m radius. One part
         * in a thousand covers that many times over, costing a mostly-rejected distance call.
         */
        private const val SLACK = 1.001

        /**
         * Samples what a degree is worth around ([lat], [lon]) under [distance]. The latitude
         * probe runs toward the equator so it can't step past a pole, and toward the equator the
         * meridian is shorter — one more reason the bound can only understate.
         */
        fun around(lat: Double, lon: Double, distance: DistanceFn): ReachBound {
            val latProbe = if (lat >= 0) -PROBE_DEGREES else PROBE_DEGREES
            return ReachBound(
                lat = lat,
                lon = lon,
                latMetersPerDegree = distance.meters(lat, lon, lat + latProbe, lon) / PROBE_DEGREES,
                lonMetersPerDegree = distance.meters(lat, lon, lat, lon + PROBE_DEGREES) / PROBE_DEGREES,
            )
        }
    }
}
