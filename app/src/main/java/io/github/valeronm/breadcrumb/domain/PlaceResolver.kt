package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place

/**
 * Resolves each derived stay to its place: stays arrive already carrying their endpoint-cluster id
 * (see [StayDeriver.Derivation]), and the clustering was *seeded* by the place pins, so a cluster's
 * [PlaceClusterer.Cluster.seedIndex] identifies its place exactly — no distance matching, labels
 * can't silently detach. The [places] list must be the same list (same order) whose pins seeded
 * the derivation; organic clusters (null seedIndex) are unnamed.
 *
 * Results are keyed by [StayDeriver.Stay.afterTrackId] — unique per stay within a derivation and
 * preserved by [StayDeriver.slicePerDay]'s copies, so resolution happens once over the unsliced
 * stays and survives day slicing.
 */
object PlaceResolver {

    class ResolvedStay(
        /** The matched place's label, or null for an unnamed cluster. */
        val label: String?,
        /** The matched place's id, set iff [label] is set. */
        val placeId: Long?,
        /** Visits to this cluster across the whole (unsliced) history. */
        val visitCount: Int,
        /** Cluster centroid — where a new place would be pinned when the user names this stay. */
        val centroid: StayDeriver.Endpoint,
    )

    /**
     * Aggregate stats for one place on the Places screen. [place] is null for an unnamed cluster
     * (still listed so it can be named); [centroid] is where naming would pin it.
     */
    data class PlaceSummary(
        val place: Place?,
        val centroid: StayDeriver.Endpoint,
        val visitCount: Int,
        /** Most recent stay end (ongoing → now); null only for a named place with no stays. */
        val lastSeenMs: Long?,
        /** Summed stay durations (ongoing → now). */
        val totalMs: Long,
        /** Cluster anchor — the pin for a named place; the capture circle's centre on a map. */
        val anchor: StayDeriver.Endpoint,
        /** The cluster's capture radius (metres). */
        val radiusM: Double,
        /** Every track endpoint captured by the cluster, for showing the scatter on a map. */
        val endpoints: List<StayDeriver.Endpoint>,
        /** This place's individual visits (unsliced), newest first — the detail screen's history. */
        val stays: List<StayDeriver.Stay> = emptyList(),
    ) {
        val isNamed: Boolean get() = place != null
    }

    fun resolve(
        stays: List<StayDeriver.Stay>,
        clusters: List<PlaceClusterer.Cluster>,
        places: List<Place>,
    ): Map<Long, ResolvedStay> {
        val result = HashMap<Long, ResolvedStay>(stays.size)
        for ((clusterId, members) in stays.groupBy { it.clusterId }) {
            val cluster = clusters[clusterId]
            val place = matchedPlace(cluster, places)
            val resolved = ResolvedStay(
                label = place?.label,
                placeId = place?.id,
                visitCount = members.size,
                centroid = cluster.centroid,
            )
            for (stay in members) {
                result[stay.afterTrackId] = resolved
            }
        }
        return result
    }

    /**
     * Summaries for the Places screen: **every visited cluster** in the history, plus any named
     * place with no current stays (so labels stay listed/manageable). Clusters that match the same
     * place are aggregated into one row; unnamed clusters get a row each. Endpoint clusters with
     * no stays (pass-through places) are skipped. Runs over the unsliced stays so counts and
     * durations are exact. Order: named places (input order) first, then unnamed clusters
     * (chronological); the UI applies its own sort.
     */
    fun summarize(
        stays: List<StayDeriver.Stay>,
        clusters: List<PlaceClusterer.Cluster>,
        places: List<Place>,
        nowMs: Long,
    ): List<PlaceSummary> {
        val namedAgg = HashMap<Long, Agg>()   // placeId -> aggregate over its matching clusters
        val unnamed = mutableListOf<PlaceSummary>()
        for ((clusterId, members) in stays.groupBy { it.clusterId }) {
            val cluster = clusters[clusterId]
            var count = 0
            var total = 0L
            var last = Long.MIN_VALUE
            for (stay in members) {
                val end = stay.end ?: nowMs
                count++
                total += end - stay.start
                last = maxOf(last, end)
            }
            val place = matchedPlace(cluster, places)
            if (place == null) {
                unnamed += PlaceSummary(
                    null, cluster.centroid, count, last, total,
                    anchor = cluster.anchor, radiusM = cluster.radiusM, endpoints = cluster.members,
                    stays = members.sortedByDescending { it.start },
                )
            } else {
                val agg = namedAgg.getOrPut(place.id) { Agg() }
                agg.count += count
                agg.total += total
                agg.last = maxOf(agg.last, last)
                agg.stays += members
            }
        }
        val named = places.mapIndexed { index, place ->
            val agg = namedAgg[place.id]
            // The place's seeded cluster — carries the pin's capture radius and every endpoint it
            // captured (including pass-throughs, which have no stays but still show on the map).
            val cluster = clusters.firstOrNull { it.seedIndex == index }
            PlaceSummary(
                place = place,
                centroid = StayDeriver.Endpoint(place.lat, place.lon),
                visitCount = agg?.count ?: 0,
                lastSeenMs = agg?.last,
                totalMs = agg?.total ?: 0L,
                anchor = StayDeriver.Endpoint(place.lat, place.lon),
                radiusM = cluster?.radiusM ?: place.radiusM,
                endpoints = cluster?.members ?: emptyList(),
                stays = agg?.stays?.sortedByDescending { it.start } ?: emptyList(),
            )
        }
        return named + unnamed
    }

    private class Agg(
        var count: Int = 0,
        var total: Long = 0L,
        var last: Long = Long.MIN_VALUE,
        val stays: MutableList<StayDeriver.Stay> = mutableListOf(),
    )

    /** The place whose pin seeded this cluster, or null for an organic (unnamed) cluster. */
    private fun matchedPlace(cluster: PlaceClusterer.Cluster, places: List<Place>): Place? =
        cluster.seedIndex?.let(places::getOrNull)
}
