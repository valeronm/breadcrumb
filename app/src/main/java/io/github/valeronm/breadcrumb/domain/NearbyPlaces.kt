package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place

/** The place rows a stay could be stated to, measured from the stay's own ends. */
object NearbyPlaces {
    private const val REACH_M = 300.0

    data class Nearby(val place: Place, val distanceM: Double, val current: Boolean)

    /**
     * Rows whose pin lies within [REACH_M] of the nearest of [ends] or whose circle covers it, and the
     * [currentId] row wherever it lies, nearest first.
     */
    fun around(ends: List<Coordinate>, places: List<Place>, currentId: Long?, distance: DistanceFn): List<Nearby> {
        if (ends.isEmpty()) return emptyList()
        return places.mapNotNull { place ->
            val nearest = ends.minOf { distance.meters(it.lat, it.lon, place.lat, place.lon) }
            val current = place.id == currentId
            val reached = current || nearest <= REACH_M || PlaceClusterer.seedOf(place).covers(nearest)
            Nearby(place, nearest, current).takeIf { reached }
        }.sortedWith(compareBy({ it.distanceM }, { it.place.id }))
    }
}
