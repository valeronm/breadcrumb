package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.DistanceFn

/**
 * Groups stay locations into *places* by anchor-based greedy leader clustering. Nothing is
 * persisted — clusters re-derive on read, in chronological input order, which makes them
 * deterministic *and* stable: a cluster's anchor is its first-ever member's location, so
 * appending new stays can never re-shuffle the clusters older stays belong to. Every member is
 * within [radius] of its anchor, so a cluster can't chain-walk across a neighbourhood.
 *
 * The user's named-place pins enter as [Seed]s: pre-existing anchors, each with its own (venue-
 * scale) capture radius, that outrank chronology. A seeded cluster's identity *is* its place
 * ([Cluster.seedIndex]), which kills the anchor lottery — a skewed first visit can no longer
 * found a shadow cluster next to a named place, because endpoints within the seed radius join
 * the pin's cluster instead. Assignment is nearest-qualifying-anchor, so an endpoint closer to
 * a distinct organic anchor still goes there.
 */
object PlaceClusterer {

    /** A pre-existing anchor — a stored place pin — with its own capture radius. */
    class Seed(
        val anchor: StayDeriver.Endpoint,
        val radiusM: Double,
    )

    class Cluster(
        /** First member's location (or the seed pin) — the stable cluster identity. */
        val anchor: StayDeriver.Endpoint,
        /** Arithmetic mean of member locations — the display/pin location. */
        val centroid: StayDeriver.Endpoint,
        /** Indices into the input list. */
        val memberIndices: List<Int>,
        /** Member locations ([memberIndices] resolved), for showing the cluster on a map. */
        val members: List<StayDeriver.Endpoint>,
        /** The capture radius this cluster admits members within (seed's own, or the default). */
        val radiusM: Double,
        /** Index into the seed list when this cluster grew from a seed; null for organic clusters. */
        val seedIndex: Int? = null,
    ) {
        val visitCount: Int get() = memberIndices.size
    }

    fun cluster(
        locations: List<StayDeriver.Endpoint>,
        radiusM: Double = DEFAULT_RADIUS_M,
        distance: DistanceFn,
        seeds: List<Seed> = emptyList(),
    ): List<Cluster> {
        val anchors = mutableListOf<StayDeriver.Endpoint>()
        val radii = mutableListOf<Double>()
        val members = mutableListOf<MutableList<Int>>()
        for (seed in seeds) {
            anchors += seed.anchor
            radii += seed.radiusM
            members += mutableListOf<Int>()
        }
        locations.forEachIndexed { index, location ->
            // Nearest qualifying anchor, scanned inline — this runs per endpoint on every derivation.
            var nearest = -1
            var nearestD = Double.MAX_VALUE
            for (ci in anchors.indices) {
                val d = distance.metres(anchors[ci].lat, anchors[ci].lon, location.lat, location.lon)
                if (d <= radii[ci] && d < nearestD) {
                    nearest = ci
                    nearestD = d
                }
            }
            if (nearest >= 0) {
                members[nearest] += index
            } else {
                anchors += location
                radii += radiusM
                members += mutableListOf(index)
            }
        }
        return anchors.mapIndexed { ci, anchor ->
            val locs = members[ci].map { locations[it] }
            Cluster(
                anchor = anchor,
                // A seed with no members keeps its pin as the centroid.
                centroid = if (locs.isEmpty()) {
                    anchor
                } else {
                    StayDeriver.Endpoint(
                        lat = locs.sumOf { it.lat } / locs.size,
                        lon = locs.sumOf { it.lon } / locs.size,
                    )
                },
                memberIndices = members[ci],
                members = locs,
                radiusM = radii[ci],
                seedIndex = ci.takeIf { it < seeds.size },
            )
        }
    }

    /**
     * 1.5× the stay agreement radius: a stay's location is a midpoint of endpoints that may be
     * up to 100 m apart, so same-place stays scatter beyond 100 m before they're truly elsewhere.
     */
    const val DEFAULT_RADIUS_M = 150.0
}
