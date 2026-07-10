package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.DistanceFn

/**
 * Groups stay locations into *places* by anchor-based greedy leader clustering. Nothing is
 * persisted — clusters re-derive on read, in chronological input order, which makes them
 * deterministic *and* stable: a cluster's anchor is its first-ever member's location, so
 * appending new stays can never re-shuffle the clusters older stays belong to. Every member is
 * within [radius] of its anchor, so a cluster can't chain-walk across a neighbourhood.
 *
 * Label matching (see [PlaceResolver]) also goes through the anchor, because the centroid drifts
 * as visits accumulate while the anchor never moves.
 */
object PlaceClusterer {

    class Cluster(
        /** First member's location — the stable identity used for place matching. */
        val anchor: StayDeriver.Endpoint,
        /** Arithmetic mean of member locations — the display/pin location. */
        val centroid: StayDeriver.Endpoint,
        /** Indices into the input list. */
        val memberIndices: List<Int>,
    ) {
        val visitCount: Int get() = memberIndices.size
    }

    fun cluster(
        locations: List<StayDeriver.Endpoint>,
        radiusM: Double = DEFAULT_RADIUS_M,
        distance: DistanceFn,
    ): List<Cluster> {
        val anchors = mutableListOf<StayDeriver.Endpoint>()
        val members = mutableListOf<MutableList<Int>>()
        locations.forEachIndexed { index, location ->
            val nearest = anchors.withIndex()
                .map { (ci, anchor) -> ci to distance.metres(anchor.lat, anchor.lon, location.lat, location.lon) }
                .filter { (_, d) -> d <= radiusM }
                .minByOrNull { (_, d) -> d }
            if (nearest != null) {
                members[nearest.first] += index
            } else {
                anchors += location
                members += mutableListOf(index)
            }
        }
        return anchors.mapIndexed { ci, anchor ->
            val locs = members[ci].map { locations[it] }
            Cluster(
                anchor = anchor,
                centroid = StayDeriver.Endpoint(
                    lat = locs.sumOf { it.lat } / locs.size,
                    lon = locs.sumOf { it.lon } / locs.size,
                ),
                memberIndices = members[ci],
            )
        }
    }

    /**
     * 1.5× the stay agreement radius: a stay's location is a midpoint of endpoints that may be
     * up to 100 m apart, so same-place stays scatter beyond 100 m before they're truly elsewhere.
     */
    const val DEFAULT_RADIUS_M = 150.0
}
