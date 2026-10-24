package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place

/**
 * What saving a hand-entered trip does about a place at each of its ends, and so what the timeline
 * will show there — decided once, for the form's preview and the save alike.
 *
 * A named place holding an end's pin is that place, since a second row there would split its stays.
 * An end picked by name names the unnamed place holding it, or else founds a place of its own. Ends
 * are judged in order against the places as the earlier ends leave them, so a round trip's two ends
 * make one place.
 */
object TripPlaces {

    /** An end's pin, and the name it was picked by where it was. */
    class End(val at: Coordinate?, val name: String?)

    sealed interface Outcome {
        /** What the timeline will call the place at this end, or null where there is none. */
        val name: String?

        /** A named place holds the pin; nothing is written. */
        data class Held(val place: Place) : Outcome {
            override val name: String? get() = place.label
        }

        /** The unnamed place holding the pin, as [row] renames it. */
        data class Names(val row: Place) : Outcome {
            override val name: String? get() = row.label
        }

        /** The place [row] creates at the pin. */
        data class Creates(val row: Place) : Outcome {
            override val name: String? get() = row.label
        }

        /** The place an earlier end creates or names holds this pin too. */
        data class Shares(override val name: String) : Outcome

        /** No place: a pin no named place holds and no name was picked for, or no pin. */
        data object None : Outcome {
            override val name: String? = null
        }
    }

    /** How a trip's end is drawn: on the track it carries straight on from, as the place it is
     *  at, or as a spot no place holds. */
    enum class Marker { POINT, PLACE, SPOT }

    /** An end carrying on a track leaves no stay, so its place goes unshown whatever it is. */
    fun markerOf(outcome: Outcome, continues: Boolean): Marker = when {
        continues -> Marker.POINT
        outcome == Outcome.None -> Marker.SPOT
        else -> Marker.PLACE
    }

    /** One [Outcome] per end, in order, and the place seeds as the save will leave them. */
    class Plan(val outcomes: List<Outcome>, val seeds: List<PlaceClusterer.Seed>) {
        val created: List<Place> get() = outcomes.mapNotNull { (it as? Outcome.Creates)?.row }
        val renamed: List<Place> get() = outcomes.mapNotNull { (it as? Outcome.Names)?.row }
    }

    /** [ends] planned in order against [places] as stored; a row created is stamped [createdAt]. */
    fun plan(ends: List<End>, places: List<Place>, distance: DistanceFn, createdAt: Long): Plan {
        // Kept as the save will leave them, so a later end resolves as the derivation will once the
        // rows are written; changed only through seedOf, the projection every other seed came through.
        val seeds = PlaceClusterer.seedsOf(places).toMutableList()
        val given = HashMap<Int, String>()
        val outcomes = ends.map { end ->
            val at = end.at ?: return@map Outcome.None
            val name = end.name?.trim()?.takeIf { it.isNotEmpty() }
            val holder = PlaceClusterer.nearestSeedIndex(at.lat, at.lon, seeds, distance)
            val stored = holder?.let(places::getOrNull)
            when {
                holder != null && holder in given -> Outcome.Shares(given.getValue(holder))
                stored != null && stored.isNamed -> Outcome.Held(stored)
                name == null -> Outcome.None
                stored != null -> {
                    val row = stored.copy(label = name, source = PlaceOrigin.MANUAL.code)
                    seeds[holder] = PlaceClusterer.seedOf(row)
                    given[holder] = name
                    Outcome.Names(row)
                }
                else -> {
                    val row = Place(
                        label = name,
                        lat = at.lat,
                        lon = at.lon,
                        createdAt = createdAt,
                        radiusM = PlaceClusterer.DEFAULT_RADIUS_M,
                        source = PlaceOrigin.MANUAL.code,
                    )
                    seeds += PlaceClusterer.seedOf(row)
                    given[seeds.lastIndex] = name
                    Outcome.Creates(row)
                }
            }
        }
        return Plan(outcomes, seeds)
    }
}
