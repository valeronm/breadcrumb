package io.github.valeronm.breadcrumb.domain

/**
 * Groups stay locations into *places* by anchor-based greedy leader clustering. Nothing is
 * persisted — clusters re-derive on read, in chronological input order, which makes them
 * deterministic *and* stable: a cluster's anchor is its first-ever member's location, so
 * appending new stays can never re-shuffle the clusters older stays belong to. Every member is
 * within [radius] of its anchor, so a cluster can't chain-walk across a neighborhood.
 *
 * The user's named-place pins enter as [Seed]s: pre-existing anchors, each with its own (venue-
 * scale) capture radius, that outrank chronology. A seeded cluster's identity *is* its place
 * ([Cluster.seedIndex]), which kills the anchor lottery — a skewed first visit can no longer
 * found a shadow cluster next to a named place, because endpoints within the seed radius join
 * the pin's cluster instead. Assignment is nearest-qualifying-anchor, so an endpoint closer to
 * a distinct organic anchor still goes there.
 */
object PlaceClusterer {

    /** A pre-existing anchor — a stored place pin — with its own capture radius. */
    class Seed(
        val anchor: StayDeriver.Endpoint,
        val radiusM: Double,
    )

    class Cluster(
        /** First member's location (or the seed pin) — the stable cluster identity. */
        val anchor: StayDeriver.Endpoint,
        /** Arithmetic mean of member locations — the display/pin location. */
        val centroid: StayDeriver.Endpoint,
        /** Indices into the input list. */
        val memberIndices: List<Int>,
        /** Member locations ([memberIndices] resolved), for showing the cluster on a map. */
        val members: List<StayDeriver.Endpoint>,
        /** The capture radius this cluster admits members within (seed's own, or the default). */
        val radiusM: Double,
        /** Index into the seed list when this cluster grew from a seed; null for organic clusters. */
        val seedIndex: Int? = null,
    ) {
        val visitCount: Int get() = memberIndices.size

        /**
         * The members' mean, or null where there are no members to average — the reading
         * [centroid] cannot give, since an empty seed keeps its pin there. Only a seeded cluster
         * can be empty: an organic one exists because an endpoint founded it.
         */
        val endpointMean: StayDeriver.Endpoint? get() = centroid.takeIf { members.isNotEmpty() }
    }

    fun cluster(
        locations: List<StayDeriver.Endpoint>,
        radiusM: Double = DEFAULT_RADIUS_M,
        distance: DistanceFn,
        seeds: List<Seed> = emptyList(),
    ): List<Cluster> {
        val anchors = mutableListOf<StayDeriver.Endpoint>()
        val radii = mutableListOf<Double>()
        val members = mutableListOf<MutableList<Int>>()
        for (seed in seeds) {
            anchors += seed.anchor
            radii += seed.radiusM
            members += mutableListOf<Int>()
        }
        locations.forEachIndexed { index, location ->
            // Nearest qualifying anchor, scanned inline — this runs per endpoint on every
            // derivation, so all but the handful of anchors in reach are rejected on their
            // coordinates ([ReachBound]) rather than on a distance call.
            val reach = ReachBound.around(location.lat, location.lon, distance)
            var nearest = -1
            var nearestD = Double.MAX_VALUE
            for (ci in anchors.indices) {
                val anchor = anchors[ci]
                if (reach.outOfReach(anchor.lat, anchor.lon, radii[ci])) continue
                val d = distance.meters(anchor.lat, anchor.lon, location.lat, location.lon)
                if (d <= radii[ci] && d < nearestD) {
                    nearest = ci
                    nearestD = d
                }
            }
            if (nearest >= 0) {
                members[nearest] += index
            } else {
                anchors += location
                radii += radiusM
                members += mutableListOf(index)
            }
        }
        return anchors.mapIndexed { ci, anchor ->
            val locs = members[ci].map { locations[it] }
            Cluster(
                anchor = anchor,
                // A seed with no members keeps its pin as the centroid.
                centroid = if (locs.isEmpty()) {
                    anchor
                } else {
                    StayDeriver.Endpoint(
                        lat = locs.sumOf { it.lat } / locs.size,
                        lon = locs.sumOf { it.lon } / locs.size,
                    )
                },
                memberIndices = members[ci],
                members = locs,
                radiusM = radii[ci],
                seedIndex = ci.takeIf { it < seeds.size },
            )
        }
    }

    /**
     * Which of [candidates] an anchor at [anchor] with [radiusM] would take, given the [rivals]
     * already anchored around it.
     *
     * Answers for one anchor what [cluster] answers for all of them, so a radius being dragged can
     * show what it captures without a write and a full re-derivation. It applies the same test —
     * *nearest qualifying anchor*, not merely within range — because "inside the circle" over-
     * promises: an endpoint sitting inside this radius but closer to a neighbor that also covers
     * it stays with the neighbor, and a preview that lit it up would be lying.
     *
     * **[rivals] are seeds — named pins — and nothing else.** Only a seed is in [cluster]'s anchor
     * list before any endpoint is read, so only a seed holds its ground whatever this radius does.
     * An organic cluster's anchor is just the first endpoint no seed claimed; ground a radius grows
     * over never produces one, because each endpoint there joins the pin as it is processed.
     * Passing organic anchors in as rivals makes them look immovable — they sit on top of their own
     * members, so they win every comparison — and a widened radius then appears to capture nothing.
     *
     * A rival takes a candidate only when it is *strictly* nearer, matching [cluster]'s `d <
     * nearestD` scan for a subject that precedes its rivals in the anchor list. Exact ties are the
     * only case where the two can disagree.
     *
     * One approximation remains: an organic anchor formed *outside* this radius can still hold an
     * endpoint inside it, if it is nearer than the pin. It has to sit within its own default radius
     * of that endpoint while itself out of reach of this one, which is a narrow band.
     *
     * This is the plain statement of the rule, kept for reading and for tests to check the
     * prepared form against; the screen uses [scanCapture].
     */
    fun wouldCapture(
        candidates: List<StayDeriver.Endpoint>,
        anchor: StayDeriver.Endpoint,
        radiusM: Double,
        rivals: List<Seed>,
        distance: DistanceFn,
    ): List<StayDeriver.Endpoint> {
        val scan = scanCapture(candidates, anchor, radiusM, rivals, distance)
        return scan.winnable.filter { it.distanceM <= radiusM }.map { it.location }
    }

    /** A candidate still in play, with the distance that decides it. */
    class Reach(val location: StayDeriver.Endpoint, val distanceM: Double)

    /**
     * [wouldCapture]'s work, done once for a radius that is about to move repeatedly.
     *
     * Dragging a radius asks the same question dozens of times over, and only one term of it
     * changes: whether a rival keeps a candidate depends on the two anchors and their radii, never
     * on ours. So the rival scan — the expensive half — runs once here, and each later step only
     * compares [Reach.distanceM] against the radius, wherever that comparison happens to live.
     */
    class CaptureScan internal constructor(
        /** Candidates in play, each carrying the distance a radius is compared against. */
        val winnable: List<Reach>,
        /**
         * Candidates a nearer rival keeps, or that no radius here could reach. Distance says
         * nothing about these — one can sit well inside the circle and still belong to the
         * neighbor — so anything drawing them must treat them as settled, not compare them.
         */
        val conceded: List<StayDeriver.Endpoint>,
    ) {
        /**
         * How many candidates a radius of [radiusM] would take — [wouldCapture]'s answer counted
         * rather than listed, and deliberately the same one predicate so the two cannot disagree.
         *
         * A dragged radius asks this on every step, and a walk is what that costs: the expensive
         * half — the rival scan and its distance calls — is already spent in [scanCapture], and
         * what remains is a comparison per candidate still in play.
         */
        fun countWithin(radiusM: Double): Int = winnable.count { it.distanceM <= radiusM }
    }

    /**
     * Prepares [candidates] for a radius sweeping between zero and [maxRadiusM].
     *
     * Candidates past [maxRadiusM] are conceded up front — no radius the caller can ask about will
     * reach them — which is what keeps the cost proportional to the neighborhood in play rather
     * than to every dot on screen.
     *
     * The [ReachBound] guards are built per *rival*, not per candidate: building one costs two
     * distance calls of its own, so a bound per candidate would spend `2N` to prune a rival list
     * that is only ever a handful of named pins. [cluster] builds it the other way round because
     * there it amortizes over every anchor in the history.
     */
    fun scanCapture(
        candidates: List<StayDeriver.Endpoint>,
        anchor: StayDeriver.Endpoint,
        maxRadiusM: Double,
        rivals: List<Seed>,
        distance: DistanceFn,
    ): CaptureScan {
        val reach = ReachBound.around(anchor.lat, anchor.lon, distance)
        val rivalReach = rivals.map { ReachBound.around(it.anchor.lat, it.anchor.lon, distance) }
        val winnable = ArrayList<Reach>(candidates.size)
        // Most candidates are conceded in the case this exists for: a dense neighborhood seen
        // through a slider that stops well short of it.
        val conceded = ArrayList<StayDeriver.Endpoint>(candidates.size)
        for (candidate in candidates) {
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
        return CaptureScan(winnable, conceded)
    }

    private fun losesTo(
        candidate: StayDeriver.Endpoint,
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
     * 1.5× the stay agreement radius: a stay's location is a midpoint of endpoints that may be
     * up to 100 m apart, so same-place stays scatter beyond 100 m before they're truly elsewhere.
     */
    const val DEFAULT_RADIUS_M = 150.0
}
