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
 *
 * Above that sit two readings of the same resolution: [summarize], one row per place for the
 * screens that list them, and [neighborhood], one place with its surroundings prepared for the
 * clusterer's capture preview. Both take summaries down to what [PlaceClusterer] speaks, which is
 * why they live on this side of the pair and not on it.
 */
object PlaceResolver {

    /**
     * Visits at which a cluster becomes notable: the timeline starts surfacing an unnamed one's
     * visit count as a naming invitation, and the Places map stops hiding it as a rare stop. One
     * constant so the two screens can't disagree about which clusters matter.
     */
    const val NOTABLE_VISIT_MIN = 3

    /**
     * How far around a place its surroundings are gathered for judging a capture radius
     * ([neighborhood]). It has to comfortably dominate the largest radius the editor's slider can
     * reach, or the preview judges a circle against neighbors that were never collected — so this
     * moves only together with those slider stops.
     */
    const val NEIGHBOR_CONTEXT_M = 1_200.0

    class ResolvedStay(
        /**
         * The matched place, or null for an unnamed cluster — the row itself rather than a copy of
         * each attribute, so a new place column reaches the timeline without a field here and the
         * label/id pair can't come apart. Its pin is deliberately *not* [centroid]: the pin is
         * where the user dropped it, the centroid is where this cluster's endpoints actually sit.
         */
        val place: Place?,
        /** Visits to this cluster across the whole (unsliced) history. */
        val visitCount: Int,
        /** Cluster centroid — where a new place would be pinned when the user names this stay. */
        val centroid: StayDeriver.Endpoint,
    ) {
        /** The matched place's label, or null for an unnamed cluster. */
        val label: String? get() = place?.label

        /** The matched place's id — non-null exactly when [label] is. */
        val placeId: Long? get() = place?.id

        /** What the place is for; null for an unnamed or untagged one. */
        val category: PlaceCategory? get() = place?.placeCategory
    }

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
        /**
         * Where the visits actually landed — the mean of [endpoints], as against [anchor], where
         * the pin was dropped; null when there is nothing to average.
         *
         * It is the clusterer's own mean ([PlaceClusterer.Cluster.endpointMean]) carried through,
         * not a second one computed here: a summary is what every screen gets in place of the
         * cluster, so a reading of the cluster that a screen needs has to be on it.
         */
        val endpointCentroid: StayDeriver.Endpoint?,
        /** This place's individual visits (unsliced), newest first — the detail screen's history. */
        val stays: List<StayDeriver.Stay> = emptyList(),
    ) {
        val isNamed: Boolean get() = place != null
    }

    /**
     * One place and what sits around it. [candidates] and [rivals] are readings of [nearby] rather
     * than parts assembled beside it, so nothing can hold a neighborhood whose rivals are not among
     * its neighbors.
     */
    class Neighborhood(
        val subject: PlaceSummary,
        /** The summaries within [NEIGHBOR_CONTEXT_M] of [subject]'s anchor. */
        val nearby: List<PlaceSummary>,
    ) {
        /** Every endpoint a radius here could take: [subject]'s own plus the loose ones around it. */
        val candidates: List<StayDeriver.Endpoint> =
            ArrayList<StayDeriver.Endpoint>(subject.endpoints.size + nearby.sumOf { it.endpoints.size })
                .apply {
                    addAll(subject.endpoints)
                    for (other in nearby) addAll(other.endpoints)
                }

        /**
         * The pins that can out-compete [subject] for a candidate — **only *named* neighbors.**
         *
         * A named place is a seed: it is in the clusterer's anchor list before any endpoint is
         * read, so it holds its ground whatever the subject's radius does. An unnamed cluster is
         * not — its anchor is merely the first endpoint no seed claimed, so ground a growing radius
         * covers never forms one at all. Counting unnamed clusters as rivals makes them look
         * immovable, since each sits on top of its own members, and a widened radius then appears
         * to capture nothing while saving it quietly takes more than was shown.
         */
        val rivals: List<PlaceClusterer.Seed> = nearby.mapNotNull { other ->
            other.place?.let { PlaceClusterer.Seed(other.anchor, other.radiusM) }
        }
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
            ResolvedStay(
                place = matchedPlace(cluster, places),
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
                    endpointCentroid = cluster.endpointMean,
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
            // Seeded clusters come first, in seed order (PlaceClusterer.cluster), and the seeds are
            // this same `places` list — so the place's cluster is at its own index. The `takeIf`
            // keeps a broken alignment fail-safe (no cluster) rather than silently mismatched.
            val cluster = clusters.getOrNull(index)?.takeIf { it.seedIndex == index }
            PlaceSummary(
                place = place,
                centroid = StayDeriver.Endpoint(place.lat, place.lon),
                visitCount = agg?.count ?: 0,
                lastSeenMs = agg?.last,
                totalMs = agg?.total ?: 0L,
                anchor = StayDeriver.Endpoint(place.lat, place.lon),
                radiusM = cluster?.radiusM ?: place.radiusM,
                endpoints = cluster?.members ?: emptyList(),
                endpointCentroid = cluster?.endpointMean,
                stays = agg?.stays?.sortedByDescending { it.start } ?: emptyList(),
            )
        }
        return named + unnamed
    }

    /**
     * Gathers what surrounds [subject] — the context a capture radius is judged against, prepared
     * for [PlaceClusterer.scanCapture].
     *
     * [all] is the full summary list and must contain [subject] itself, which is excluded by
     * identity.
     */
    fun neighborhood(subject: PlaceSummary, all: List<PlaceSummary>, distance: DistanceFn): Neighborhood {
        // [all] is one row per cluster in the whole history, while the answer is a handful of
        // anchors: reject on coordinates first, and the distance call runs only for those.
        val reach = ReachBound.around(subject.anchor.lat, subject.anchor.lon, distance)
        val nearby = all.filter { other ->
            other !== subject &&
                !reach.outOfReach(other.anchor.lat, other.anchor.lon, NEIGHBOR_CONTEXT_M) &&
                distance.meters(
                    other.anchor.lat, other.anchor.lon,
                    subject.anchor.lat, subject.anchor.lon,
                ) <= NEIGHBOR_CONTEXT_M
        }
        return Neighborhood(subject, nearby)
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
