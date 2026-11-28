package io.github.valeronm.breadcrumb.domain.places

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.DistanceFn

object PlaceOverlap {

    /** The rows besides [place] whose circle overlaps its circle, nearest first. */
    fun candidatesFor(place: Place, places: List<Place>, distance: DistanceFn): List<Place> =
        places
            .filter { it.id != place.id }
            .map { it to distance.meters(place.lat, place.lon, it.lat, it.lon) }
            .filter { (other, meters) -> meters < place.radiusM + other.radiusM }
            .sortedBy { (_, meters) -> meters }
            .map { (other, _) -> other }
}
