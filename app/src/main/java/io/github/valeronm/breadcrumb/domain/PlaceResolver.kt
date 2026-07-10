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

    fun resolve(
        stays: List<StayDeriver.Stay>,
        places: List<Place>,
        radiusM: Double = PlaceClusterer.DEFAULT_RADIUS_M,
        distance: DistanceFn,
    ): Map<Long, ResolvedStay> {
        val clusters = PlaceClusterer.cluster(stays.map { it.location }, radiusM, distance)
        val result = HashMap<Long, ResolvedStay>(stays.size)
        for (cluster in clusters) {
            val place = places
                .map { it to distance.metres(it.lat, it.lon, cluster.anchor.lat, cluster.anchor.lon) }
                .filter { (_, d) -> d <= radiusM }
                .minByOrNull { (_, d) -> d }
                ?.first
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
}
