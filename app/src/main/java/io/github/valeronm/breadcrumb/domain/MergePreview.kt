package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place

/**
 * Where each visit of a set of overlapping places lands if [dots]' `absorbed` places are merged into
 * the keeper, predicted without a write: the merge states an absorbed place's stated ends to the
 * keeper and leaves its measured ends to the nearest remaining circle covering them.
 *
 * Organic anchors are not predicted: one covering a measured end takes it from any seed not strictly
 * nearer, whatever [Lands] this gives it ([PlaceClusterer.Anchoring.claim]).
 */
object MergePreview {
    /** [NO_PLACE] is outside every remaining place row's circle. */
    enum class Lands { KEEPER, OTHER_PLACE, NO_PLACE }

    class Dot(val at: Coordinate, val lands: Lands, val stated: Boolean)

    /** [endpoints] is every end [place]'s cluster holds, and [stated] those of them stated to it. */
    class Member(val place: Place, val endpoints: List<Coordinate>, val stated: List<Coordinate>)

    /**
     * One [Dot] per endpoint of [members], in their order. [places] is every place row in the
     * history, since a measured end leaving an absorbed circle can land in any of them.
     */
    fun dots(
        members: List<Member>,
        keeperId: Long,
        absorbed: Set<Long>,
        places: List<Place>,
        distance: DistanceFn,
    ): List<Dot> {
        val remaining = PlaceClusterer.seedsOf(places.filter { it.id !in absorbed })
        val index = PlaceClusterer.SeedIndex(remaining)
        val landing = { at: Coordinate ->
            when (val nearest = index.nearest(at.lat, at.lon, distance)) {
                null -> Lands.NO_PLACE
                else -> if (remaining[nearest].placeId == keeperId) Lands.KEEPER else Lands.OTHER_PLACE
            }
        }
        return members.flatMap { dotsOf(it, keeperId, absorbed, landing) }
    }

    private fun dotsOf(
        member: Member,
        keeperId: Long,
        absorbed: Set<Long>,
        landing: (Coordinate) -> Lands,
    ): List<Dot> {
        val id = member.place.id
        val pending = HashMap<Coordinate, Int>()
        for (at in member.stated) pending.merge(at, 1, Int::plus)
        val out = ArrayList<Dot>()
        for (at in member.endpoints) {
            val left = pending[at] ?: 0
            val stated = left > 0
            if (stated) pending[at] = left - 1
            val lands = when {
                id !in absorbed -> if (id == keeperId) Lands.KEEPER else Lands.OTHER_PLACE
                stated -> Lands.KEEPER
                else -> landing(at)
            }
            out += Dot(at, lands, stated)
        }
        return out
    }

    /** The nearest named place among [opened] and [overlapping] that no import made, else [opened]. */
    fun initialKeeper(opened: Place, overlapping: List<Place>, distance: DistanceFn): Place =
        (listOf(opened) + overlapping)
            .filter { it.isNamed && it.externalProvider == null }
            .minByOrNull { distance.meters(opened.lat, opened.lon, it.lat, it.lon) }
            ?: opened
}
