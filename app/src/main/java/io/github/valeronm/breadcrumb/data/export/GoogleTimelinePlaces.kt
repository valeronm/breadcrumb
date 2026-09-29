package io.github.valeronm.breadcrumb.data.export

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.DistanceFn
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.placeCategory

/**
 * The places a Google Timeline export supports: one per Google place with a top-level visit, and one
 * for a home or a work seen only nested. Google labels each visit rather than each place, and leaves a
 * known home unlabelled on a share of its visits, so a place is a home or a work on a strict majority
 * of its labelled visits.
 */
internal object GoogleTimelinePlaces {

    /** [category] is null for a place that is neither a home nor a work. */
    class Candidate(val placeId: String, val category: PlaceCategory?, val lat: Double, val lon: Double)

    private class Tally {
        var home = 0
        var work = 0
        var labelled = 0
        var topLevel = false
        var latest: GoogleTimelineVisit? = null
    }

    fun places(visits: List<GoogleTimelineVisit>): List<Candidate> {
        val tallies = LinkedHashMap<String, Tally>()
        for (visit in visits) {
            val tally = tallies.getOrPut(visit.placeId) { Tally() }
            if (visit.topLevel) tally.topLevel = true
            // A place's pin moves in discrete revisions; the latest is where Google now puts it,
            // where an average of revisions lands somewhere no revision was.
            if (tally.latest.let { it == null || visit.startMs >= it.startMs }) tally.latest = visit
            when (visit.semanticType) {
                "HOME", "INFERRED_HOME" -> {
                    tally.home++
                    tally.labelled++
                }
                "WORK", "INFERRED_WORK" -> {
                    tally.work++
                    tally.labelled++
                }
                null, "UNKNOWN" -> Unit
                else -> tally.labelled++
            }
        }
        return tallies.mapNotNull { (placeId, tally) ->
            val category = when {
                tally.home * 2 > tally.labelled -> PlaceCategory.HOME
                tally.work * 2 > tally.labelled -> PlaceCategory.WORK
                else -> null
            }
            if (category == null && !tally.topLevel) return@mapNotNull null
            val pin = checkNotNull(tally.latest)
            Candidate(placeId, category, pin.at.lat, pin.at.lon)
        }
    }

    /**
     * The existing row each candidate is, keyed by Google place id: the row an earlier import made for
     * that Google place, else, for a home or a work, a place of the same category whose circle holds
     * the candidate's pin, the nearest where several do. Only Google's own id, or a category both sides
     * state, is trusted to make two pins one place.
     */
    fun matchExisting(candidates: List<Candidate>, existing: List<Place>, distance: DistanceFn): Map<String, Long> {
        val imported = existing.filter { it.externalProvider == GoogleTimelineImporter.PROVIDER }
            .mapNotNull { p -> p.externalId?.let { it to p.id } }
            .toMap()
        val byCategory = existing.groupBy { it.placeCategory }
        val matches = HashMap<String, Long>()
        for (candidate in candidates) {
            val known = imported[candidate.placeId]
            if (known != null) {
                matches[candidate.placeId] = known
                continue
            }
            val same = byCategory[candidate.category ?: continue] ?: continue
            PlaceClusterer.nearestSeedIndex(candidate.lat, candidate.lon, PlaceClusterer.seedsOf(same), distance)
                ?.let { matches[candidate.placeId] = same[it].id }
        }
        return matches
    }
}
