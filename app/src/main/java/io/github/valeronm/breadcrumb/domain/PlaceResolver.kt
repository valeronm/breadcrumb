package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.DistanceFn
import io.github.valeronm.breadcrumb.data.db.Place

/**
 * Resolves each derived stay to its place: cluster the stay locations ([PlaceClusterer]), then
 * match each cluster to the nearest persisted [Place] within [radius] of the cluster's *anchor*.
 * The anchor, not the centroid: a place is pinned at the centroid when named, but the centroid
 * drifts as visits accumulate — the anchor is guaranteed within radius of the pin at naming time
 * and never moves, so labels can't silently detach.
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
    ) {
        val isNamed: Boolean get() = place != null
    }

    fun resolve(
        stays: List<StayDeriver.Stay>,
        places: List<Place>,
        radiusM: Double = PlaceClusterer.DEFAULT_RADIUS_M,
        distance: DistanceFn,
    ): Map<Long, ResolvedStay> {
        val clusters = PlaceClusterer.cluster(stays.map { it.location }, radiusM, distance)
        val result = HashMap<Long, ResolvedStay>(stays.size)
        for (cluster in clusters) {
            val place = matchedPlace(cluster, places, radiusM, distance)
            val resolved = ResolvedStay(
                label = place?.label,
                placeId = place?.id,
                visitCount = cluster.visitCount,
                centroid = cluster.centroid,
            )
            for (index in cluster.memberIndices) {
                result[stays[index].afterTrackId] = resolved
            }
        }
        return result
    }

    /**
     * Summaries for the Places screen: **every cluster** in the history, plus any named place with
     * no current stays (so labels stay listed/manageable). Clusters that match the same place are
     * aggregated into one row; unnamed clusters get a row each. Runs over the unsliced stays so
     * counts and durations are exact. Order: named places (input order) first, then unnamed
     * clusters (chronological); the UI applies its own sort.
     */
    fun summarize(
        stays: List<StayDeriver.Stay>,
        places: List<Place>,
        nowMs: Long,
        radiusM: Double = PlaceClusterer.DEFAULT_RADIUS_M,
        distance: DistanceFn,
    ): List<PlaceSummary> {
        val clusters = PlaceClusterer.cluster(stays.map { it.location }, radiusM, distance)
        val namedAgg = HashMap<Long, Agg>()   // placeId -> aggregate over its matching clusters
        val unnamed = mutableListOf<PlaceSummary>()
        for (cluster in clusters) {
            var count = 0
            var total = 0L
            var last = Long.MIN_VALUE
            for (index in cluster.memberIndices) {
                val stay = stays[index]
                val end = stay.end ?: nowMs
                count++
                total += end - stay.start
                last = maxOf(last, end)
            }
            val place = matchedPlace(cluster, places, radiusM, distance)
            if (place == null) {
                unnamed += PlaceSummary(null, cluster.centroid, count, last, total)
            } else {
                val agg = namedAgg.getOrPut(place.id) { Agg() }
                agg.count += count
                agg.total += total
                agg.last = maxOf(agg.last, last)
            }
        }
        val named = places.map { place ->
            val agg = namedAgg[place.id]
            PlaceSummary(
                place = place,
                centroid = StayDeriver.Endpoint(place.lat, place.lon),
                visitCount = agg?.count ?: 0,
                lastSeenMs = agg?.last,
                totalMs = agg?.total ?: 0L,
            )
        }
        return named + unnamed
    }

    private class Agg(var count: Int = 0, var total: Long = 0L, var last: Long = Long.MIN_VALUE)

    /** The nearest place within [radiusM] of the cluster's anchor, or null. */
    private fun matchedPlace(
        cluster: PlaceClusterer.Cluster,
        places: List<Place>,
        radiusM: Double,
        distance: DistanceFn,
    ): Place? = places
        .map { it to distance.metres(it.lat, it.lon, cluster.anchor.lat, cluster.anchor.lon) }
        .filter { (_, d) -> d <= radiusM }
        .minByOrNull { (_, d) -> d }
        ?.first
}
