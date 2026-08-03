package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * The named places at a track's two ends — where the journey set out from and where it arrived. What
 * a route is annotated with, a line between two anonymous dots saying only *that* it went somewhere.
 *
 * A place holds an end when that end sits inside its capture radius, and the **nearest** such place
 * wins — literally [PlaceClusterer.nearestSeedIndex], the predicate that admits a stay, because it is
 * the same question about the same coordinate. Reusing the place's own radius leaves the user one
 * knob: narrowing a place to a doorway keeps other people's stops out of it *and* stops it claiming
 * the arrivals of journeys that merely finished down the street.
 *
 * Asked of the *fixes*, not of the derived timeline. The stay either side of a track resolves to a
 * place through the full clustering, which also holds anchors this cannot see — an organic cluster
 * founded by some earlier visit can sit nearer an end than the pin does, and clustering would send
 * the stay there. But an organic cluster has no pin, so on a map it is nothing to draw: the named
 * answer is the only one this can show, and it is reached without waiting behind the most expensive
 * computation in the app. It can be *called* something — the timeline and the Places list fall back
 * to the city a cluster sits in ([PlaceResolver.ResolvedStay.name]) — and a label over a route's end
 * is still a claim that a place is there, which a city is not.
 *
 * Ignored fixes are the caller's business — pass the points that are drawn, so an end that moved when
 * the overrun came off it is the end the map marks.
 */
object RoutePlaces {

    /**
     * The place at [points]' start followed by the place at its end, each dropped where no place
     * holds that end. **Deduplicated**: a round trip home is one place, drawn once — the line already
     * says it came back, and two pins on one spot would only fight over the same label.
     */
    fun ends(points: List<TrackPoint>, places: List<Place>, distance: DistanceFn): List<Place> {
        if (points.isEmpty()) return emptyList()
        // One projection for both ends: it is the same pins answering the same question twice.
        val seeds = PlaceClusterer.seedsOf(places)
        return listOfNotNull(
            holderOf(points.first(), seeds, places, distance),
            holderOf(points.last(), seeds, places, distance),
        ).distinctBy { it.id }
    }

    /**
     * The place whose capture area holds [point], or null where none does — the question [ends] asks
     * of each end, for a caller that needs the two answers apart rather than deduplicated into a set
     * of pins to draw.
     */
    fun holding(point: TrackPoint, places: List<Place>, distance: DistanceFn): Place? =
        holderOf(point, PlaceClusterer.seedsOf(places), places, distance)

    // Through the seeds, not the rows: a pin and its reach are the whole of what claims a
    // coordinate, and reading the columns here would be a second reader of a place's geometry
    // outside the one projection that is allowed to know them. [seeds] must be [places]' own, in
    // order — the index comes back positional.
    private fun holderOf(
        point: TrackPoint,
        seeds: List<PlaceClusterer.Seed>,
        places: List<Place>,
        distance: DistanceFn,
    ): Place? =
        PlaceClusterer.nearestSeedIndex(point.latitude, point.longitude, seeds, distance)
            ?.let(places::get)
}
