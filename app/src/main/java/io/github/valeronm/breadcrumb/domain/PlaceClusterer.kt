package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place

/**
 * Groups stay locations into *places* by anchor-based greedy leader clustering. Nothing is
 * persisted — clusters re-derive on read in chronological input order, deterministic *and* stable:
 * an anchor is its first-ever member's location, so appending new stays never re-shuffles the
 * clusters older stays belong to, and every member is within [radius] of its anchor, so a cluster
 * can't chain-walk across a neighborhood. The place pins enter as [Seed]s —
 * pre-existing anchors with their own venue-scale capture radii that outrank chronology; a seeded
 * cluster's identity *is* its place ([Cluster.seedIndex]), killing the anchor lottery — a skewed
 * first visit cannot found a shadow cluster next to a place, since endpoints within the seed
 * radius join the pin's cluster (assignment is nearest-qualifying-anchor, so an endpoint closer to
 * a distinct organic anchor still goes there).
 */
object PlaceClusterer {

    /**
     * A pre-existing anchor — a stored place pin — with its own capture radius. **A value**: two
     * seeds describing the same pin are the same seed, which is what lets a caller ask whether a
     * fresh reading of the places table would cluster any differently by comparing the projections
     * rather than the rows. As a plain class it would compare by identity, and a
     * `distinctUntilChanged` over freshly built seeds would differ every time — gating nothing, and
     * saying nothing about it. Compared by value nowhere inside this object, which indexes seeds
     * positionally throughout.
     */
    data class Seed(
        val anchor: Coordinate,
        val radiusM: Double,
        /** The place row this seed is, or null for an anchor no place holds. */
        val placeId: Long? = null,
    )

    /**
     * What clustering reads off a stored place: where it sits, how far it reaches, and which row it
     * is. **This is the whole of a place's influence on the derivation** — its name and its category
     * reach clustering nowhere — so an observer that re-derives only when this projection changes
     * cannot miss a move and cannot re-run on a rename. One function rather than a `Seed(…)` at each such observer,
     * because a term added here has to reach every one of them or the ones it misses stop noticing
     * what they exist to notice.
     */
    fun seedOf(place: Place): Seed = Seed(place.pin, place.radiusM, place.id)

    /**
     * **Where a cluster reports itself to be**, from its members' sums and count — with [anchor] the
     * answer when it has none, a seed keeping its pin until something joins it. One function because
     * a cluster is arrived at two ways, derived from a list of members and read back from stored
     * running sums, and a spot that moved on one path and not the other is a place that names itself
     * differently on the Places map than on the timeline row for the same stop.
     */
    fun centroidOf(sumLat: Double, sumLon: Double, count: Int, anchor: Coordinate): Coordinate =
        if (count == 0) anchor else Coordinate(sumLat / count, sumLon / count)

    /** [seedOf] over a list, in the list's order — which is the order clustering seeds in. */
    fun seedsOf(places: List<Place>): List<Seed> = places.map(::seedOf)

    /**
     * Which of [seeds] claims ([lat], [lon]): the nearest one whose own radius covers it, or null
     * where none does. **The single statement of the rule** — [cluster] admits an endpoint to a
     * seeded cluster by it, [StayDeriver] decides two endpoints are the same place by it through
     * [SeedIndex], which is tested against it, and a track's ends are named by it ([RoutePlaces]) —
     * so the inclusive radius and the nearest-wins tie-break are settled in one spot rather than
     * agreeing three times by comment.
     *
     * Scanned in one pass returning an index, not a [Seed]: handing back a pin-with-distance would
     * box a `Double` per call. Seeds out of reach cost coordinate arithmetic, not a distance call
     * ([ReachBound]).
     *
     * [cluster] keeps its own copy of the scan deliberately: its anchor list grows as it walks, so it
     * compares against organic anchors this cannot see and would have to allocate a [Seed] per
     * endpoint in the hot path to ask.
     */
    fun nearestSeedIndex(
        lat: Double,
        lon: Double,
        seeds: List<Seed>,
        distance: DistanceFn,
    ): Int? {
        if (seeds.isEmpty()) return null
        val reach = ReachBound.around(lat, lon, distance)
        var nearest = -1
        var nearestM = Double.MAX_VALUE
        for (i in seeds.indices) {
            val seed = seeds[i]
            if (reach.outOfReach(seed.anchor.lat, seed.anchor.lon, seed.radiusM)) continue
            val meters = distance.meters(seed.anchor.lat, seed.anchor.lon, lat, lon)
            if (meters <= seed.radiusM && meters < nearestM) {
                nearest = i
                nearestM = meters
            }
        }
        return nearest.takeIf { it >= 0 }
    }

    /**
     * [nearestSeedIndex] for a fixed seed list asked about many points, with the same answer, ties
     * included. The seeds are held sorted by latitude, so a point reads only the band its widest
     * radius can reach rather than every seed, which can number well over a thousand.
     */
    class SeedIndex(private val seeds: List<Seed>) {
        private val order: IntArray = seeds.indices.sortedBy { seeds[it].anchor.lat }.toIntArray()
        private val lats = DoubleArray(order.size) { seeds[order[it]].anchor.lat }
        private val widestM = seeds.maxOfOrNull { it.radiusM } ?: 0.0

        fun nearest(lat: Double, lon: Double, distance: DistanceFn): Int? {
            if (seeds.isEmpty()) return null
            return indexNearest(lat, lon, ReachBound.around(lat, lon, distance), distance).takeIf { it >= 0 }
        }

        /** [nearest] around a [reach] the caller already holds, as an index or -1. */
        internal fun indexNearest(lat: Double, lon: Double, reach: ReachBound, distance: DistanceFn): Int {
            val span = reach.latitudeSpan(widestM)
            var nearest = -1
            var nearestM = Double.MAX_VALUE
            var k = firstAtOrAbove(lat - span)
            while (k < lats.size && lats[k] <= lat + span) {
                val i = order[k++]
                val seed = seeds[i]
                if (reach.outOfReach(seed.anchor.lat, seed.anchor.lon, seed.radiusM)) continue
                val meters = distance.meters(seed.anchor.lat, seed.anchor.lon, lat, lon)
                if (meters > seed.radiusM) continue
                // The band is walked out of index order, so a tie goes to the lower index as the
                // plain scan's strict comparison leaves it.
                if (meters < nearestM || meters == nearestM && i < nearest) {
                    nearest = i
                    nearestM = meters
                }
            }
            return nearest
        }

        private fun firstAtOrAbove(lat: Double): Int {
            var low = 0
            var high = lats.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (lats[mid] < lat) low = mid + 1 else high = mid
            }
            return low
        }
    }

    class Cluster(
        /** First member's location (or the seed pin) — the stable cluster identity. */
        val anchor: Coordinate,
        /** Arithmetic mean of member locations — the display/pin location. */
        val centroid: Coordinate,
        /**
         * Indices into the input list, for a caller that has to say *which* endpoints these are —
         * empty when the cluster was read back from storage rather than derived, where the input
         * list it would index does not exist. [members] is the same set either way.
         */
        val memberIndices: List<Int>,
        /** Member locations ([memberIndices] resolved), for showing the cluster on a map. */
        val members: List<Coordinate>,
        /** The capture radius this cluster admits members within (seed's own, or the default). */
        val radiusM: Double,
        /** Index into the seed list when this cluster grew from a seed; null for organic clusters. */
        val seedIndex: Int? = null,
    ) {
        /** Counted off [members], which every cluster has — [memberIndices] only a derived one. */
        val visitCount: Int get() = members.size

        /** What this cluster is to the clustering: an anchor with a reach — see [seedOf], which
         *  says why that projection is never spelled at a call site. */
        val seed: Seed get() = Seed(anchor, radiusM)

        /**
         * The members' mean, or null where there are no members to average — the reading
         * [centroid] cannot give, since an empty seed keeps its pin there. Only a seeded cluster
         * can be empty: an organic one exists because an endpoint founded it.
         */
        val endpointMean: Coordinate? get() = centroid.takeIf { members.isNotEmpty() }
    }

    /**
     * The anchors an endpoint can join, **growing as endpoints found new ones**. That growth is the
     * rule, not an implementation detail: an endpoint no anchor reaches becomes one, and every later
     * endpoint sees it — which is what makes a pass that appends to a history agree with one that
     * walks it from the start.
     *
     * Held as parallel lists rather than [Seed]s because [cluster] runs this per endpoint over the
     * whole history, and a `Seed` per endpoint is an allocation in the hot path for nothing. The
     * seeds never change once given, so they are searched through a [SeedIndex] and only the anchors
     * founded since are scanned.
     */
    class Anchoring(seeds: List<Seed>, private val defaultRadiusM: Double, private val distance: DistanceFn) {
        private val points = seeds.mapTo(mutableListOf()) { it.anchor }
        private val radii = seeds.mapTo(mutableListOf()) { it.radiusM }
        private val seedCount = seeds.size
        private val seedIndex = SeedIndex(seeds)

        /** Built on the first stated claim: a pass over recorded history makes none. */
        private val seedOfPlace: Map<Long, Int> by lazy {
            buildMap { seeds.forEachIndexed { index, seed -> seed.placeId?.let { put(it, index) } } }
        }

        /** How many anchors there are — the seeded ones first, then each founded since. */
        val size: Int get() = points.size

        /** The anchor at [index] as a seed. Built on the way out, never on the way in: this is what
         *  a caller wants once per cluster, where [claim] runs once per endpoint. */
        fun seedAt(index: Int): Seed = Seed(points[index], radii[index])

        /**
         * The index of the anchor claiming [at] — **nearest qualifying**, not merely within range —
         * founding one there when none reaches it. All but the handful in reach are rejected on
         * their coordinates ([ReachBound]) rather than on a distance call. A location stated to
         * [placeId] joins that place's seed as given, neither measuring its distance nor founding an
         * anchor; a place with no seed here leaves it to the radii.
         */
        fun claim(at: Coordinate, placeId: Long?): Int {
            placeId?.let(seedOfPlace::get)?.let { return it }
            val reach = ReachBound.around(at.lat, at.lon, distance)
            var nearest = seedIndex.indexNearest(at.lat, at.lon, reach, distance)
            var nearestD = if (nearest < 0) {
                Double.MAX_VALUE
            } else {
                distance.meters(points[nearest].lat, points[nearest].lon, at.lat, at.lon)
            }
            // Strictly nearer only: every seed precedes these, and a tie goes to the lower index.
            for (index in seedCount until points.size) {
                if (reach.outOfReach(points[index].lat, points[index].lon, radii[index])) continue
                val d = distance.meters(points[index].lat, points[index].lon, at.lat, at.lon)
                if (d <= radii[index] && d < nearestD) {
                    nearest = index
                    nearestD = d
                }
            }
            if (nearest >= 0) return nearest
            points += at
            radii += defaultRadiusM
            return points.size - 1
        }
    }

    fun cluster(
        locations: List<Coordinate>,
        radiusM: Double = DEFAULT_RADIUS_M,
        distance: DistanceFn,
        seeds: List<Seed> = emptyList(),
        /** The place the location at an index is stated to, or null to leave it to the radii. */
        statedPlaceAt: (Int) -> Long? = { null },
    ): List<Cluster> {
        val anchoring = Anchoring(seeds, radiusM, distance)
        val members = MutableList(seeds.size) { mutableListOf<Int>() }
        locations.forEachIndexed { index, location ->
            val claimed = anchoring.claim(location, statedPlaceAt(index))
            // claim() founds at most one anchor per call, and does so at the end.
            if (claimed == members.size) members += mutableListOf<Int>()
            members[claimed] += index
        }
        return List(anchoring.size) { ci ->
            val seed = anchoring.seedAt(ci)
            val locs = members[ci].map { locations[it] }
            Cluster(
                anchor = seed.anchor,
                centroid = centroidOf(locs.sumOf { it.lat }, locs.sumOf { it.lon }, locs.size, seed.anchor),
                memberIndices = members[ci],
                members = locs,
                radiusM = seed.radiusM,
                seedIndex = ci.takeIf { it < seeds.size },
            )
        }
    }

    /**
     * Which of [candidates] an anchor at [anchor] with [radiusM] would take, given the [rivals]
     * already anchored around it — for one anchor what [cluster] answers for all, so a radius
     * being dragged can show what it captures without a write and a full re-derivation. It applies
     * the same test — *nearest qualifying anchor*, not merely within range — because "inside the
     * circle" over-promises: an endpoint inside this radius but closer to a neighbor that also
     * covers it stays with the neighbor, and a preview that lit it up would be lying.
     *
     * **[rivals] are seeds — place pins — and nothing else.** Only a seed is in [cluster]'s anchor
     * list before any endpoint is read, so only a seed holds its ground whatever this radius does;
     * an organic cluster's anchor is just the first endpoint no seed claimed, and ground a radius
     * grows over never produces one — each endpoint there joins the pin as it is processed.
     * Passing organic anchors in as rivals makes them look immovable (they sit on their own
     * members and win every comparison), and a widened radius then appears to capture nothing.
     * A rival takes a candidate only when *strictly* nearer, matching [cluster]'s `d < nearestD`
     * scan for a subject preceding its rivals in the anchor list; exact ties are the only case
     * where the two can disagree. One approximation remains: an organic anchor formed *outside*
     * this radius can still hold an endpoint inside it when nearer than the pin — it must sit
     * within its own default radius of the endpoint while out of reach of this one, a narrow band.
     * The plain statement of the rule, kept for reading and as the reference [scanCapture] is
     * tested against.
     */
    fun wouldCapture(
        candidates: List<Coordinate>,
        anchor: Coordinate,
        radiusM: Double,
        rivals: List<Seed>,
        distance: DistanceFn,
    ): List<Coordinate> {
        val scan = scanCapture(candidates, anchor, radiusM, Contest(rivals), distance)
        return scan.held + scan.winnable.filter { it.distanceM <= radiusM }.map { it.location }
    }

    /** A candidate still in play, with the distance that decides it. */
    class Reach(val location: Coordinate, val distanceM: Double)

    /**
     * Candidates whose place is stated rather than measured: [here] are ends stated to the place
     * being judged, which it holds at any radius, and [elsewhere] ends stated to another place,
     * which that place holds whatever this radius does.
     */
    class Stated(val here: List<Coordinate>, val elsewhere: List<Coordinate>) {
        companion object {
            val None = Stated(emptyList(), emptyList())
        }
    }

    /** What else claims a candidate: the [rivals]' pins by distance, and [stated] by statement. */
    class Contest(val rivals: List<Seed>, val stated: Stated = Stated.None)

    /**
     * [wouldCapture]'s work, done once for a radius about to move repeatedly. Dragging asks the
     * same question dozens of times over and only one term changes — whether a rival keeps a
     * candidate depends on the two anchors and their radii, never on ours — so the expensive rival
     * scan runs once here, and each later step only compares [Reach.distanceM] against the radius,
     * wherever that comparison happens to live.
     */
    class CaptureScan internal constructor(
        /** Candidates in play, each carrying the distance a radius is compared against. */
        val winnable: List<Reach>,
        /**
         * Candidates a nearer rival keeps, or that no radius here could reach. Distance says
         * nothing about these — one can sit well inside the circle and still belong to the
         * neighbor — so anything drawing them must treat them as settled, not compare them.
         */
        val conceded: List<Coordinate>,
        /** Candidates stated to this place, taken at any radius. */
        val held: List<Coordinate>,
    ) {
        /**
         * How many candidates a radius of [radiusM] would take — [wouldCapture]'s answer counted
         * rather than listed, deliberately the same one predicate so the two cannot disagree. A
         * dragged radius asks this on every step and pays only a walk: the rival scan and its
         * distance calls are already spent in [scanCapture], leaving a comparison per candidate.
         */
        fun countWithin(radiusM: Double): Int = held.size + winnable.count { it.distanceM <= radiusM }

        /**
         * The mean of what a radius of [radiusM] would take — where a pin following the dragged
         * circle would sit — or null when it would take nothing. Same walk and predicate as
         * [countWithin], so the two describe one set: no count of nothing with a position anyway,
         * nor the reverse.
         */
        fun centroidWithin(radiusM: Double): Coordinate? {
            var lat = held.sumOf { it.lat }
            var lon = held.sumOf { it.lon }
            var taken = held.size
            for (reach in winnable) {
                if (reach.distanceM > radiusM) continue
                lat += reach.location.lat
                lon += reach.location.lon
                taken++
            }
            return if (taken == 0) null else Coordinate(lat / taken, lon / taken)
        }
    }

    /**
     * Prepares [candidates] for a radius sweeping between zero and [maxRadiusM]. Candidates past
     * [maxRadiusM] are conceded up front — no radius the caller can ask about will reach them —
     * which keeps the cost proportional to the neighborhood in play rather than every dot on
     * screen. The [ReachBound] guards are built per *rival*, not per candidate: building one costs
     * two distance calls, so a bound per candidate would spend `2N` to prune a rival list that is
     * only ever a handful of place pins; [cluster] builds it the other way round because there it
     * amortizes over every anchor in the history.
     *
     * A candidate in [Contest.stated] is settled by its statement, as the derivation settles it: one
     * occurrence of it among [candidates] per occurrence there, and a stated coordinate that is not a
     * candidate adds nothing.
     */
    fun scanCapture(
        candidates: List<Coordinate>,
        anchor: Coordinate,
        maxRadiusM: Double,
        contest: Contest,
        distance: DistanceFn,
    ): CaptureScan {
        val rivals = contest.rivals
        val stated = contest.stated
        val reach = ReachBound.around(anchor.lat, anchor.lon, distance)
        val rivalReach = rivals.map { ReachBound.around(it.anchor.lat, it.anchor.lon, distance) }
        val winnable = ArrayList<Reach>(candidates.size)
        // Most candidates are conceded in the case this exists for: a dense neighborhood seen
        // through a slider that stops well short of it.
        val conceded = ArrayList<Coordinate>(candidates.size)
        val held = ArrayList<Coordinate>(stated.here.size)
        // Per coordinate, the statements still to match: to this place first, then elsewhere.
        val pending = HashMap<Coordinate, IntArray>()
        for (at in stated.here) pending.getOrPut(at) { IntArray(2) }[0]++
        for (at in stated.elsewhere) pending.getOrPut(at) { IntArray(2) }[1]++
        for (candidate in candidates) {
            val left = pending[candidate]
            if (left != null && left[0] > 0) {
                left[0]--
                held += candidate
                continue
            }
            if (left != null && left[1] > 0) {
                left[1]--
                conceded += candidate
                continue
            }
            if (reach.outOfReach(candidate.lat, candidate.lon, maxRadiusM)) {
                conceded += candidate
                continue
            }
            val own = distance.meters(anchor.lat, anchor.lon, candidate.lat, candidate.lon)
            if (own > maxRadiusM || losesTo(candidate, own, rivals, rivalReach, distance)) {
                conceded += candidate
            } else {
                winnable += Reach(candidate, own)
            }
        }
        return CaptureScan(winnable, conceded, held)
    }

    private fun losesTo(
        candidate: Coordinate,
        own: Double,
        rivals: List<Seed>,
        rivalReach: List<ReachBound>,
        distance: DistanceFn,
    ): Boolean {
        for (i in rivals.indices) {
            val rival = rivals[i]
            if (rivalReach[i].outOfReach(candidate.lat, candidate.lon, rival.radiusM)) continue
            val theirs = distance.meters(
                rival.anchor.lat, rival.anchor.lon, candidate.lat, candidate.lon,
            )
            if (theirs <= rival.radiusM && theirs < own) return true
        }
        return false
    }

    /**
     * How far apart one spot's endpoints may sit and still be filed as that spot — a different bay
     * in the car park, a fix that landed a street early.
     *
     * **What it governs is attribution, not agreement.** Two endpoints within
     * [StayDeriver.Params.agreementRadiusM] make a stay whatever the clustering decides, that test
     * being a disjunction, so shrinking this cannot turn stays into gaps. What it does is split one
     * place in two: the stay is filed under the cluster of its *earlier* endpoint, and a spot whose
     * scatter exceeds this radius accumulates two clusters, halving its visit count, listing it
     * twice on the Places tab and dividing its nights between them. Widening has the opposite
     * failure — neighbouring spots merge into one — and no radius suits every venue, which is why a
     * place carries [Place.radiusM] of its own and this is only where an organic cluster starts.
     *
     * Also the capture radius a newly created place is given, so it is read well outside the
     * clustering. [StayDeriver.Params.placeRadiusM] defaults back to it, so the ratio to the
     * agreement radius is prose either way and nothing enforces it.
     */
    const val DEFAULT_RADIUS_M = 150.0
}
