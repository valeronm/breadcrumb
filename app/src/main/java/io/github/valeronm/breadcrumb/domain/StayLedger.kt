package io.github.valeronm.breadcrumb.domain

/**
 * Repairs a stored derivation around a change, instead of deriving the history again.
 *
 * A track finishing, being deleted, merged or rewritten touches the endpoints of a handful of
 * tracks and the intervals between them — everything before and after is untouched, because
 * clustering appends rather than reshuffles (a cluster's anchor is its first-ever member) and an
 * interval is a fact about two adjacent tracks. So the work is proportional to the seam, not to the
 * history.
 *
 * **It must agree with [StayDeriver.derive] exactly**, since the two write the same rows and only
 * one of them runs at a time. What keeps them honest is that the rule they share is written once —
 * [StayDeriver.Agreement] decides what "the same place" means for both — and that the consistency
 * guard re-derives from scratch and compares. Where this cannot agree is stated rather than
 * hidden: a cluster's anchor is its first member, so deleting the track that founded one leaves the
 * anchor where it was, while a fresh derivation would put it on whatever endpoint now comes first.
 */
object StayLedger {

    /** A cluster to assign against: where it sits, how far it reaches, and what it holds. */
    class ClusterRow(
        val id: Long,
        val anchor: Coordinate,
        val radiusM: Double,
        /** Named clusters are seeds — they survive with no members, and they are the pins the
         *  shared-pin agreement override reads. */
        val named: Boolean,
        val memberCount: Int,
    )

    /** Which cluster an endpoint belongs to: one already stored, or one this pass founds. */
    sealed interface ClusterRef {
        data class Stored(val id: Long) : ClusterRef

        /** An index into [Mutations.founded] — resolved to a row id when that row is inserted. */
        data class Founded(val index: Int) : ClusterRef
    }

    /** One endpoint's membership, as stored or as this pass would store it. */
    class Membership(
        val trackId: Long,
        val isStart: Boolean,
        val at: Coordinate,
        val atMs: Long,
        val cluster: ClusterRef,
    )

    /** A cluster this pass founds because no stored one reached the endpoint that made it. */
    class FoundedCluster(val anchor: Coordinate, val radiusM: Double)

    /** How one stored cluster's membership moved — applied to its running sums and count. */
    class ClusterDelta(val id: Long, val sumLat: Double, val sumLon: Double, val count: Int)

    /**
     * An interval to store: the verdict, its bounds, and the clusters it names — as [ClusterRef],
     * since one of them may be a row this same pass is about to insert.
     *
     * Flat rather than a [StayDeriver.Interval] with the refs beside it, because a stay's cluster is
     * *part of* what a stay is: wrapping one would mean building it with a cluster index that does
     * not exist yet, and a domain value with a placeholder in the field its own KDoc calls "the only
     * answer it has to where it was" is worse than a row shape that never pretends.
     */
    class Interval(
        val verdict: StayDeriver.Verdict,
        val start: Long,
        val end: Long,
        val afterTrackId: Long,
        val ends: Ends,
    ) {
        val cluster: ClusterRef? get() = ends.cluster
        val toCluster: ClusterRef? get() = ends.toCluster
        val from: Coordinate? get() = ends.from
        val to: Coordinate? get() = ends.to
    }

    /**
     * Where an interval's two ends were, and which clusters claimed them. A stay fills [cluster]
     * alone — one place is what a stay *is*. A gap fills all four, its two positions being what the
     * add-trip form runs a hand-entered leg between, and either cluster being absent on a side no
     * fix was had for, which is what makes it an unknown-endpoint gap.
     */
    class Ends(
        val cluster: ClusterRef?,
        val toCluster: ClusterRef?,
        val from: Coordinate?,
        val to: Coordinate?,
    )

    /**
     * The rows a change takes away, each named by the key that identifies it rather than by row id,
     * so a caller applies them without reading anything back first.
     */
    class Removals(
        /** Intervals, by the track each follows. */
        val intervalsAfterTracks: List<Long>,
        /** Memberships, by the track whose endpoints they are. */
        val membershipsOfTracks: List<Long>,
        /** Unnamed clusters left holding nothing, which is what ends one. A named cluster stays:
         *  it is the user's, and its emptiness is a fact about the history rather than a mistake. */
        val emptiedClusters: List<Long>,
    )

    /** What to write: what goes, and what arrives in its place. */
    class Mutations(
        val removed: Removals,
        val intervals: List<Interval>,
        val memberships: List<Membership>,
        val founded: List<FoundedCluster>,
        val deltas: List<ClusterDelta>,
    )

    /** The stretch of history a change reaches, in time order. */
    class Seam(
        /** The kept track before the change, whose following interval is recomputed. */
        val prev: StayDeriver.TrackEnd?,
        val removed: List<StayDeriver.TrackEnd>,
        val added: List<StayDeriver.TrackEnd>,
        /** The kept track after the change; null when the change reaches the end of the history,
         *  where the interval that would follow is the open one and is never stored. */
        val next: StayDeriver.TrackEnd?,
    )

    /** Everything already stored that the seam is judged against. */
    class Stored(
        val clusters: List<ClusterRow>,
        /** Memberships of [Seam.prev], [Seam.next] and every removed track, by track id. */
        val membershipOf: Map<Long, List<Membership>>,
        val liveness: List<StayDeriver.Liveness>,
        val nowMs: Long,
    )

    fun reknit(
        seam: Seam,
        stored: Stored,
        params: StayDeriver.Params = StayDeriver.Params(),
        distance: DistanceFn,
    ): Mutations {
        val pins = stored.clusters.filter { it.named }
            .map { PlaceClusterer.Seed(it.anchor, it.radiusM) }
        val agreement = StayDeriver.Agreement(params, distance, pins)
        val evidence = StayDeriver.summarizeLiveness(stored.liveness, stored.nowMs)

        val anchors = Anchors(stored.clusters, params.placeRadiusM, distance)
        val deltas = HashMap<Long, ClusterDelta>()
        fun shift(id: Long, at: Coordinate, by: Int) {
            val current = deltas[id]
            deltas[id] = ClusterDelta(
                id = id,
                sumLat = (current?.sumLat ?: 0.0) + at.lat * by,
                sumLon = (current?.sumLon ?: 0.0) + at.lon * by,
                count = (current?.count ?: 0) + by,
            )
        }

        // What leaves: the removed tracks' endpoints stop counting toward their clusters.
        for (track in seam.removed) {
            for (member in stored.membershipOf[track.trackId].orEmpty()) {
                (member.cluster as? ClusterRef.Stored)?.let { shift(it.id, member.at, -1) }
            }
        }

        // What arrives: each endpoint joins the nearest cluster that reaches it, or founds one.
        val memberships = mutableListOf<Membership>()
        val clusterOf = HashMap<Endpoint, ClusterRef>()
        for (endpoint in StayDeriver.endpointsOf(seam.added)) {
            val ref = anchors.claim(endpoint.at)
            memberships += Membership(endpoint.trackId, endpoint.isStart, endpoint.at, endpoint.atMs, ref)
            clusterOf[Endpoint(endpoint.trackId, endpoint.isStart)] = ref
            (ref as? ClusterRef.Stored)?.let { shift(it.id, endpoint.at, +1) }
        }
        for (id in listOfNotNull(seam.prev?.trackId, seam.next?.trackId)) {
            for (member in stored.membershipOf[id].orEmpty()) {
                clusterOf[Endpoint(id, member.isStart)] = member.cluster
            }
        }

        // The intervals over the repaired stretch — the pairs the change can have altered, and no
        // others. An interval after the last track in the chain is the open one, which is never
        // stored ([StayDeriver.tail]).
        val chain = listOfNotNull(seam.prev) + seam.added + listOfNotNull(seam.next)
        val intervals = chain.zipWithNext().mapNotNull { (before, after) ->
            intervalBetween(before, after, clusterOf, agreement, evidence)
        }

        return Mutations(
            removed = Removals(
                intervalsAfterTracks = (listOfNotNull(seam.prev) + seam.added + seam.removed)
                    .map { it.trackId },
                membershipsOfTracks = (seam.added + seam.removed).map { it.trackId },
                emptiedClusters = stored.clusters
                    .filterNot { it.named }
                    .filter { it.memberCount + (deltas[it.id]?.count ?: 0) <= 0 }
                    .map { it.id },
            ),
            intervals = intervals,
            memberships = memberships,
            founded = anchors.founded,
            deltas = deltas.values.toList(),
        )
    }

    /**
     * The interval between two adjacent kept tracks, by the same rules [StayDeriver.derive] applies
     * between any other pair: endpoints that agree leave a stay at the cluster the earlier one is
     * in, endpoints that disagree leave a gap carrying both positions, and a disagreement with no
     * time in it leaves nothing at all.
     */
    private fun intervalBetween(
        before: StayDeriver.TrackEnd,
        after: StayDeriver.TrackEnd,
        clusterOf: Map<Endpoint, ClusterRef>,
        agreement: StayDeriver.Agreement,
        evidence: StayDeriver.LivenessEvidence,
    ): Interval? {
        val from = clusterOf[Endpoint(before.trackId, isStart = false)]
        val to = clusterOf[Endpoint(after.trackId, isStart = true)]
        val verdict = StayDeriver.verdictBetween(
            before, after, sameCluster = from != null && from == to, agreement, evidence,
        )
        if (verdict is StayDeriver.Verdict.None) return null
        val moved = verdict is StayDeriver.Verdict.Moved
        return Interval(
            verdict = verdict,
            start = before.endedAt,
            end = after.startedAt,
            afterTrackId = before.trackId,
            ends = Ends(
                cluster = from,
                toCluster = if (moved) to else null,
                from = if (moved) before.end else null,
                to = if (moved) after.start else null,
            ),
        )
    }

    /** Which end of which track — the key an endpoint's cluster is held under. */
    private data class Endpoint(val trackId: Long, val isStart: Boolean)

    /**
     * The clusters an endpoint can join, growing as endpoints found new ones — which is what makes
     * an incremental pass agree with the chronological one: [PlaceClusterer] admits an endpoint to
     * the *nearest anchor that reaches it* and otherwise founds an anchor there, and a later
     * endpoint sees that new anchor exactly as it would have in a full derivation.
     */
    private class Anchors(
        private val stored: List<ClusterRow>,
        radiusM: Double,
        distance: DistanceFn,
    ) {
        private val anchoring = PlaceClusterer.Anchoring(
            stored.map { PlaceClusterer.Seed(it.anchor, it.radiusM) }, radiusM, distance,
        )
        val founded = mutableListOf<FoundedCluster>()

        /**
         * Which cluster an endpoint joins, by [PlaceClusterer.Anchoring]'s rule and no other — the
         * stored rows enter as its seeds, so an index past them is a cluster this pass founded.
         */
        fun claim(endpoint: Coordinate): ClusterRef {
            val index = anchoring.claim(endpoint)
            if (index < stored.size) return ClusterRef.Stored(stored[index].id)
            while (founded.size <= index - stored.size) {
                founded += FoundedCluster(anchoring.anchorAt(stored.size + founded.size), anchoring.radiusAt(index))
            }
            return ClusterRef.Founded(index - stored.size)
        }
    }
}
