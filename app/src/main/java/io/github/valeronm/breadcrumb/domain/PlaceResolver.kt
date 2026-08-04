package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place

/**
 * Resolves endpoint clusters to places: stays and gaps arrive already carrying their cluster ids
 * (see [StayDeriver.Derivation]), and the clustering was *seeded* by the place pins, so a cluster's
 * [PlaceClusterer.Cluster.seedIndex] identifies its place exactly — no distance matching, labels
 * can't silently detach. The [places] list must be the same list (same order) whose pins seeded
 * the derivation; organic clusters (null seedIndex) are unnamed. [resolveClusters] results are
 * indexed by cluster id, which [StayDeriver.slicePerDay]'s copies preserve on each stay — so
 * resolution runs once over the unsliced stays and consumers look it up per interval. Above that
 * sit two readings of the same resolution: [summarize] (one row per place for the screens that
 * list them) and [neighborhood] (one place with its surroundings prepared for the clusterer's
 * capture preview); both take summaries down to what [PlaceClusterer] speaks, which is why they
 * live on this side of the pair and not on it.
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
     * ([neighborhood]). Must comfortably dominate the largest radius the editor's slider reaches —
     * else the preview judges a circle against neighbors never collected — so it moves only with
     * those slider stops.
     */
    const val NEIGHBOR_CONTEXT_M = 2_000.0

    /**
     * How far the endpoint mean has to sit from the pin before re-centering is worth offering.
     * A shift smaller than this is inside the scatter the mean is made of — the endpoints are GPS
     * fixes, and no arrangement of them locates a place to better than a few meters.
     */
    private const val RECENTER_MIN_SHIFT_M = 10.0

    /**
     * Stable identity for a place: its row id once named, its position while only a cluster — a
     * name is the only thing that survives re-derivation unchanged, and an unnamed cluster has
     * nothing but its position. Coordinates go in rounded to about a meter, so a key doesn't churn
     * on movement smaller than the fixes it is averaged from. Private on purpose: [PlaceSummary.key]
     * and [ResolvedStay.key] are the only ways to ask, so no caller ever decides for itself which
     * fields identify a place, and the two cannot name the same cluster differently.
     */
    private fun placeKey(placeId: Long?, position: StayDeriver.Endpoint): String =
        placeId?.let(::keyOf) ?: "cluster:%.5f,%.5f".format(position.lat, position.lon)

    /**
     * The key a row that has just been inserted will answer to, before any derivation has seen it.
     * Its one caller is a screen that asked for a place to be created and has to follow it there:
     * [reacquire] can only track a cluster through naming by *position*, and a pin the user placed by
     * hand is exactly the case where the position moved — leaving the id as the only thing that still
     * identifies what was named. This stays the single source of the format either way.
     */
    fun keyOf(placeId: Long): String = "place:$placeId"

    /**
     * What to call a stop: **the user's own name for it, else the city it sits in.**
     *
     * The precedence is the opposite of [TravelNaming]'s, and deliberately so. A journey is to a
     * city and not to the hotel someone slept in, so there the city wins; a stop here *is* the
     * hotel — the exact spot, with its own arrival and departure — and a name the user chose for it
     * is the better answer by every measure. The city is what the app can say when nobody has said
     * anything, and a reader still needs the label to tell the two apart: a derived name must not
     * render as one the user gave.
     *
     * Private for the reason [placeKey] is: [ResolvedStay] and [PlaceSummary] can describe the same
     * cluster on two screens at once, and a rule each decided for itself is how one stop comes to be
     * called two things.
     */
    private fun displayName(place: Place?, city: String?): String? = place?.label ?: city

    /**
     * **Where a place sits: the pin once named, [whileUnnamed] until then.** The two readings below
     * describe the same stop on two screens, so they ask this rather than each holding a ternary —
     * a refinement to one of a matched pair is how one stop comes to be drawn at two coordinates,
     * and there is no test that can catch it.
     */
    private fun pinOf(place: Place?, whileUnnamed: StayDeriver.Endpoint): StayDeriver.Endpoint =
        place?.let { StayDeriver.Endpoint(it.lat, it.lon) } ?: whileUnnamed

    /**
     * The stays that are visits. **A stop of no duration is not one**: two tracks sharing an instant
     * leave a stay with no time in it — a split's cut, or a trip entered by hand landing exactly on
     * the absence it fills — and that describes a join between two tracks rather than time spent
     * anywhere. Left in the derivation and dropped here, where visits are counted, for the reason
     * [StayDeriver] gives: every threshold lives with the reader that wants one.
     *
     * Not a duration floor, and the distinction matters — a floor is what that same KDoc warns
     * against, and it would empty the three readers keeping their own. However brief, a stop the
     * endpoints agree on is a visit; only the interval with no duration at all goes.
     *
     * Deliberately *not* the question [TimelineItem.StayItem.isBareSeam] asks, which also wants to
     * know whether the row carries a merge offer. That decides whether a **row** is worth drawing;
     * this decides whether a **visit** happened, and a seam the user could merge away is no more a
     * visit than one they can't.
     */
    private fun visitsAmong(stays: List<StayDeriver.Stay>): List<StayDeriver.Stay> =
        stays.filter { it.end != it.start }

    class ResolvedStay(
        /**
         * The matched place, or null for an unnamed cluster — the row itself rather than a copy
         * per attribute, so a new place column reaches the timeline without a field here and the
         * label/id pair can't come apart. Its pin is deliberately *not* [centroid]: the pin is
         * where the user dropped it, the centroid where this cluster's endpoints actually sit.
         */
        val place: Place?,
        /** Visits to this cluster across the whole (unsliced) history. */
        val visitCount: Int,
        /** Cluster centroid — where a new place would be pinned when the user names this stay. */
        val centroid: StayDeriver.Endpoint,
        /**
         * Where on earth this cluster sits, as the gazetteer has it — the row itself rather than a
         * copy per attribute, following [place]: what a spot is called and which clock it runs on
         * are two questions about one answer, and a third should not need a field here. Null only
         * when the caller offered no gazetteer, or when nothing in it reaches this coordinate.
         *
         * **Filled whether or not a place claims the cluster.** A user names a spot; they do not
         * decide which country it is in or what time it is there, and "Mum's" in Tokyo runs on
         * Tokyo's clock. What a label outranks is the *name* below, and nothing else.
         */
        val locality: CityAtlas.City? = null,
    ) {
        /** The matched place's label, or null for an unnamed cluster. */
        val label: String? get() = place?.label

        /** Where this stop sits — see [pinOf]; unnamed, that is the middle of what the cluster
         *  captured. */
        val pin: StayDeriver.Endpoint get() = pinOf(place, centroid)

        /** The city this cluster sits in, named or not — see [locality]. */
        val city: String? get() = locality?.name

        /** The zone this stop's clock ran on, or null where nothing places it. */
        val zoneId: String? get() = locality?.zoneId

        /** What to call this stop — see [displayName], which decides it for both readings. */
        val name: String? get() = displayName(place, city)

        /** The matched place's id — non-null exactly when [label] is. */
        val placeId: Long? get() = place?.id

        /** What the place is for; null for an unnamed or untagged one. */
        val category: PlaceCategory? get() = place?.placeCategory

        /**
         * This cluster's stable identity — the same string [PlaceSummary.key] gives for it, so a
         * stay row and a Places row open the same place.
         */
        val key: String get() = placeKey(placeId, centroid)
    }

    /**
     * Aggregate stats for one place on the Places screen. [place] is null for an unnamed cluster,
     * still listed so it can be named.
     */
    data class PlaceSummary(
        val place: Place?,
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
         * the pin was dropped; null when there is nothing to average. The clusterer's own mean
         * ([PlaceClusterer.Cluster.endpointMean]) carried through rather than recomputed here, and
         * what [pin] answers with for an unnamed cluster — where a place sits when nothing has
         * said where to put it.
         */
        val endpointCentroid: StayDeriver.Endpoint?,
        /** This place's individual visits (unsliced), newest first — the detail screen's history. */
        val stays: List<StayDeriver.Stay> = emptyList(),
        /** Where on earth this place sits, as the gazetteer has it — see [ResolvedStay.locality]. */
        val locality: CityAtlas.City? = null,
    ) {
        val isNamed: Boolean get() = place != null

        /** The city this place sits in, named or not — see [locality]. */
        val city: String? get() = locality?.name

        /** The zone this place's clock runs on, or null where nothing places it — a visit here is
         *  read on it, the same as the timeline row for that visit. */
        val zoneId: String? get() = locality?.zoneId

        /** What to call this place — see [displayName], which decides it for both readings. */
        val name: String? get() = displayName(place, city)

        /**
         * Where this place sits — see [pinOf]; unnamed, that is the mean of what it captured, which
         * is exactly where naming would drop the pin. Derived, because a summary that carried it
         * could hold a position its own anchor and endpoints disagree with. The [anchor] fallback
         * cannot fire — only a seeded cluster can be empty of endpoints, and a seeded cluster is
         * named — it is there because the mean is typed nullable for that case.
         */
        val pin: StayDeriver.Endpoint get() = pinOf(place, endpointCentroid ?: anchor)

        /** This place's stable identity — see [placeKey]. Held rather than computed per read: an
         *  unnamed cluster's key is a formatted string, and it is read once per row by a list key,
         *  once per candidate by [reacquire]'s scan, and again by every timeline row. */
        val key: String = placeKey(place?.id, pin)
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
         * A named place is a seed, in the clusterer's anchor list before any endpoint is read, so
         * it holds its ground whatever the subject's radius does; an unnamed cluster's anchor is
         * merely the first endpoint no seed claimed, so ground a growing radius covers never forms
         * one at all. Counting unnamed clusters as rivals makes them look immovable (each sits on
         * its own members), and a widened radius then appears to capture nothing while saving it
         * quietly takes more than was shown.
         */
        val rivals: List<PlaceClusterer.Seed> = nearby.mapNotNull { other ->
            other.place?.let { PlaceClusterer.Seed(other.anchor, other.radiusM) }
        }
    }

    /**
     * Resolution of *every* endpoint cluster, indexed by cluster id — stays look up by
     * [StayDeriver.Stay.clusterId], gaps by their side cluster ids (whose clusters may have no
     * stays at all; those resolve with a zero visit count).
     *
     * [cities] is the gazetteer's answer for a cluster's centroid, keyed by that centroid — a lookup
     * and deliberately **not** a list parallel to [clusters]. An index-parallel array is the one
     * coupling this file exists to avoid (see the class KDoc): it cannot be checked, it survives a
     * caller that filters or reorders clusters, and what it produces is one cluster's city printed
     * on another. Empty for the callers that want the resolution and not the gazetteer.
     *
     * Applied to every cluster, claimed or not ([ResolvedStay.locality]) — a label is a name, not a
     * statement about where on earth the spot is. What a label outranks lives in [displayName], and
     * there only, so there is exactly one rule about it and no gate here to disagree with it.
     */
    fun resolveClusters(
        stays: List<StayDeriver.Stay>,
        clusters: List<PlaceClusterer.Cluster>,
        places: List<Place>,
        cities: Map<StayDeriver.Endpoint, CityAtlas.City> = emptyMap(),
    ): List<ResolvedStay> {
        val visitsByCluster = visitsAmong(stays).groupingBy { it.clusterId }.eachCount()
        return clusters.mapIndexed { clusterId, cluster ->
            ResolvedStay(
                place = matchedPlace(cluster, places),
                visitCount = visitsByCluster[clusterId] ?: 0,
                centroid = cluster.centroid,
                locality = cities[cluster.centroid],
            )
        }
    }

    /**
     * Summaries for the Places screen: **every visited cluster** in the history, plus any named
     * place with no current stays (so labels stay listed/manageable). Clusters matching the same
     * place aggregate into one row; unnamed clusters get a row each, *including* zero-visit
     * pass-throughs — gap sides land in exactly those (a stray endpoint cluster only ever produces
     * disagreements, so it never earns a stay), and the detail screen needs a row to open so the
     * stray can be named or swallowed by widening a neighbor; keeping them off the Places tab is
     * that screen's presentation filter, not this layer's. Runs over unsliced stays so counts and
     * durations are exact; order: named (input order) then unnamed (chronological), the UI sorting.
     *
     * [cities] is the gazetteer, keyed and applied exactly as [resolveClusters] describes — so a
     * cluster reads the same on the Places list as it does on a timeline row.
     */
    fun summarize(
        stays: List<StayDeriver.Stay>,
        clusters: List<PlaceClusterer.Cluster>,
        places: List<Place>,
        nowMs: Long,
        cities: Map<StayDeriver.Endpoint, CityAtlas.City> = emptyMap(),
    ): List<PlaceSummary> {
        val staysByCluster = visitsAmong(stays).groupBy { it.clusterId }
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
                    null, count, last.takeIf { count > 0 }, total,
                    anchor = cluster.anchor, radiusM = cluster.radiusM, endpoints = cluster.members,
                    endpointCentroid = cluster.endpointMean,
                    stays = members.sortedByDescending { it.start },
                    locality = cities[cluster.centroid],
                )
            } else if (count > 0) {
                // Zero-stay seeded clusters add nothing: the place row below reports null/zero
                // stats via the missing Agg.
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
                visitCount = agg?.count ?: 0,
                lastSeenMs = agg?.last,
                totalMs = agg?.total ?: 0L,
                anchor = StayDeriver.Endpoint(place.lat, place.lon),
                radiusM = cluster?.radiusM ?: place.radiusM,
                endpoints = cluster?.members ?: emptyList(),
                endpointCentroid = cluster?.endpointMean,
                stays = agg?.stays?.sortedByDescending { it.start } ?: emptyList(),
                // From the cluster the pin seeded, not the pin: the gazetteer was asked about
                // centroids, and a seeded cluster with no members has the two in the same spot.
                locality = cluster?.let { cities[it.centroid] },
            )
        }
        return named + unnamed
    }

    /**
     * Where re-centering a pin at [anchor] would move it — the middle of what a radius of
     * [radiusM] takes — or null when the move isn't worth offering: nothing captured to average,
     * or a middle already sitting on the pin. The coincident case is the ordinary one, not a
     * corner: naming a cluster pins the place at exactly this mean, so a place named and not since
     * revisited has the two in the same spot. The middle is taken from [scan] at [radiusM] rather
     * than passed in, so the answer always describes the circle on screen rather than the one last
     * saved — a caller cannot pair a pin with a mean measured from somewhere else.
     */
    fun recenterTarget(
        anchor: StayDeriver.Endpoint,
        scan: PlaceClusterer.CaptureScan,
        radiusM: Double,
        distance: DistanceFn,
    ): StayDeriver.Endpoint? =
        scan.centroidWithin(radiusM)?.takeIf {
            distance.meters(it.lat, it.lon, anchor.lat, anchor.lon) >= RECENTER_MIN_SHIFT_M
        }

    /**
     * Finds the place a screen is showing in a freshly derived list — [key] is what it was opened
     * with, [previous] what it last resolved to. Identity alone is not enough: naming is the one
     * act that changes a place's key — the cluster it was is gone and a `place:` row stands where
     * it stood. So a dead key falls back to position, which naming leaves exactly where it was —
     * failing that, the screen keeps what it had rather than emptying while a derivation catches up.
     */
    fun reacquire(
        summaries: List<PlaceSummary>,
        key: String?,
        previous: PlaceSummary?,
    ): PlaceSummary? =
        summaries.firstOrNull { it.key == key }
            ?: previous?.let { was -> summaries.firstOrNull { it.pin == was.pin } }
            ?: previous

    /**
     * Gathers what surrounds [subject] — the context a capture radius is judged against, prepared
     * for [PlaceClusterer.scanCapture] — once, around where the place is currently saved: a dragged
     * pin stays well inside it. [all] is the full summary list, [subject] included — excluded by
     * position, so a summary rebuilt by a re-derivation still recognises itself.
     */
    fun neighborhood(subject: PlaceSummary, all: List<PlaceSummary>, distance: DistanceFn): Neighborhood {
        // [all] is one row per cluster in the whole history, while the answer is a handful of
        // anchors: coordinates rule out nearly everything, and the distance call runs only for
        // what survives that.
        val reach = ReachBound.around(subject.anchor.lat, subject.anchor.lon, distance)
        val nearby = all.filter { other ->
            other.pin != subject.pin &&
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
