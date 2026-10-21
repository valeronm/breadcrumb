package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * The places at a track's two ends — where the journey set out from and where it arrived. What a
 * route is annotated with, a line between two anonymous dots saying only *that* it went somewhere.
 *
 * An end stated to a place is held by that place, as the derivation files it, named or not: the
 * statement is a fact about the trip whatever the place is called. Otherwise a place holds an end
 * when that end sits inside its capture radius, and the **nearest** such place wins — literally
 * [PlaceClusterer.nearestSeedIndex], the predicate that admits a stay, because it is the same
 * question about the same coordinate. Reusing the place's own radius leaves the user one
 * knob: narrowing a place to a doorway keeps other people's stops out of it *and* stops it claiming
 * the arrivals of journeys that merely finished down the street.
 *
 * Asked of the *fixes*, not of the derived timeline. The stay either side of a track resolves to a
 * place through the full clustering, which also holds anchors this cannot see — an organic cluster
 * founded by some earlier visit can sit nearer an end than the pin does, and clustering would send
 * the stay there. But an organic cluster has no pin, so on a map it is nothing to draw: a place
 * row's answer is the only one this can show, and it is reached without waiting behind the most
 * expensive computation in the app. It can be *called* something — the timeline and the Places list fall back
 * to the city a cluster sits in ([PlaceResolver.ResolvedStay.name]) — and a label over a route's end
 * is still a claim that a place is there, which a city is not.
 *
 * Ignored fixes are the caller's business — pass the points that are drawn, so an end that moved when
 * the overrun came off it is the end the map marks.
 */
object RoutePlaces {

    /** What a track's two ends show on a map. */
    class Route(val places: List<Place>, val links: List<Link>)

    data class Link(val from: Coordinate, val to: Coordinate)

    /**
     * [Route.places] is the place at [points]' start followed by the place at its end, each dropped
     * where none holds it or where a radius holds it for an unnamed place. **Deduplicated**: a round
     * trip home is one place, drawn once — the line already says it came back, and two pins on one
     * spot would only fight over the same label.
     *
     * [Route.links] joins each end stated to a place whose capture radius does not reach that end's
     * fix to the place's pin: a statement holds an end at any distance, and outside the circle
     * nothing else on a map joins the two.
     */
    fun route(
        points: List<TrackPoint>,
        places: List<Place>,
        distance: DistanceFn,
        startPlaceId: Long?,
        endPlaceId: Long?,
    ): Route {
        if (points.isEmpty()) return Route(emptyList(), emptyList())
        // One projection for both ends: it is the same pins answering the same question twice.
        val seeds = PlaceClusterer.seedsOf(places)
        val ends = listOf(
            endOf(points.first(), startPlaceId, seeds, places, distance),
            endOf(points.last(), endPlaceId, seeds, places, distance),
        )
        return Route(ends.mapNotNull { it.place }.distinctBy { it.id }, ends.mapNotNull { it.link })
    }

    /**
     * The place holding [point], or null where none does — the question [route] asks of each end,
     * for a caller that needs the two answers apart rather than deduplicated into a set of pins to
     * draw.
     */
    fun holding(
        point: TrackPoint,
        places: List<Place>,
        distance: DistanceFn,
        statedPlaceId: Long?,
    ): Place? = endOf(point, statedPlaceId, PlaceClusterer.seedsOf(places), places, distance).place

    private class End(val place: Place?, val link: Link?)

    // Through the seeds, not the rows: a pin and its reach are the whole of what claims a
    // coordinate, and reading the columns here would be a second reader of a place's geometry
    // outside the one projection that is allowed to know them. [seeds] must be [places]' own, in
    // order — the index comes back positional.
    private fun endOf(
        point: TrackPoint,
        stated: Long?,
        seeds: List<PlaceClusterer.Seed>,
        places: List<Place>,
        distance: DistanceFn,
    ): End {
        val statedAt = stated?.let { id -> places.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
        if (statedAt != null) {
            val seed = seeds[statedAt]
            val meters = distance.meters(seed.anchor.lat, seed.anchor.lon, point.latitude, point.longitude)
            val link = if (seed.covers(meters)) null else Link(Coordinate(point.latitude, point.longitude), seed.anchor)
            return End(places[statedAt], link)
        }
        val held = PlaceClusterer.nearestSeedIndex(point.latitude, point.longitude, seeds, distance)
            ?.let(places::get)
            ?.takeIf { it.isNamed }
        return End(held, null)
    }
}
