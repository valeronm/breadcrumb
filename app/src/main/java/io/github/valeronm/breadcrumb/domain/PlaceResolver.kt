package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place

/**
 * Resolves endpoint clusters to places: stays and gaps arrive already carrying their cluster ids
 * (see [StayDeriver.Derivation]), and the clustering was *seeded* by the place pins, so a cluster's
 * [PlaceClusterer.Cluster.seedIndex] identifies its place exactly — no distance matching, labels
 * can't silently detach. The [places] list must be the same list (same order) whose pins seeded
 * the derivation; organic clusters (null seedIndex) are unnamed.
 *
 * [resolveClusters] results are indexed by cluster id, which [StayDeriver.slicePerDay]'s copies
 * preserve on each stay — so resolution runs once over the unsliced stays and consumers look it
 * up per interval afterwards.
 */
object PlaceResolver {

    /**
     * Visits at which an unnamed cluster becomes notable: the timeline starts surfacing its visit
     * count as a naming invitation, and the Places screens stop treating it as a rare stop. One
     * constant so the two screens can't disagree about which clusters matter.
     */
    const val NOTABLE_VISIT_MIN = 3

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
        /** Cluster anchor — the pin for a named place; the capture circle's center on a map. */
        val anchor: StayDeriver.Endpoint,
        /** The cluster's capture radius (meters). */
        val radiusM: Double,
        /** Every track endpoint captured by the cluster, for showing the scatter on a map. */
        val endpoints: List<StayDeriver.Endpoint>,
        /** This place's individual visits (unsliced), newest first — the detail screen's history. */
        val stays: List<StayDeriver.Stay> = emptyList(),
    ) {
        val isNamed: Boolean get() = place != null
    }

    /**
     * Resolution of *every* endpoint cluster, indexed by cluster id — stays look up by
     * [StayDeriver.Stay.clusterId], gaps by their side cluster ids (whose clusters may have no
     * stays at all; those resolve with a zero visit count).
     */
    fun resolveClusters(
        stays: List<StayDeriver.Stay>,
        clusters: List<PlaceClusterer.Cluster>,
        places: List<Place>,
    ): List<ResolvedStay> {
        val visitsByCluster = stays.groupingBy { it.clusterId }.eachCount()
        return clusters.mapIndexed { clusterId, cluster ->
            val place = matchedPlace(cluster, places)
            ResolvedStay(
                label = place?.label,
                placeId = place?.id,
                visitCount = visitsByCluster[clusterId] ?: 0,
                centroid = cluster.centroid,
            )
        }
    }

    /**
     * Summaries for the Places screen: **every visited cluster** in the history, plus any named
     * place with no current stays (so labels stay listed/manageable). Clusters that match the same
     * place are aggregated into one row; unnamed clusters get a row each, *including* zero-visit
     * pass-through clusters — gap sides land in exactly those (a stray endpoint cluster only
     * ever produces disagreements, so it never earns a stay), and the detail screen needs a row
     * to open so the stray can be named or swallowed by widening a neighbor. Keeping
     * pass-throughs off the Places tab is that screen's presentation filter, not this layer's.
     * Runs over the unsliced stays so counts and durations are exact. Order: named places
     * (input order) first, then unnamed clusters (chronological); the UI applies its own sort.
     */
    fun summarize(
        stays: List<StayDeriver.Stay>,
        clusters: List<PlaceClusterer.Cluster>,
        places: List<Place>,
        nowMs: Long,
    ): List<PlaceSummary> {
        val staysByCluster = stays.groupBy { it.clusterId }
        val namedAgg = HashMap<Long, Agg>()   // placeId -> aggregate over its matching clusters
        val unnamed = mutableListOf<PlaceSummary>()
        clusters.forEachIndexed { clusterId, cluster ->
            val members = staysByCluster[clusterId].orEmpty()
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
                    null, cluster.centroid, count, last.takeIf { count > 0 }, total,
                    anchor = cluster.anchor, radiusM = cluster.radiusM, endpoints = cluster.members,
                    stays = members.sortedByDescending { it.start },
                )
            } else if (count > 0) {
                // Zero-stay seeded clusters add nothing: the place row below reports null/zero
                // stats via the missing Agg, exactly as before.
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
