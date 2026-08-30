package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackEndpoints

/** A finished track as the deriver reads it: an interval and the two coordinates bounding it. */
fun TrackEndpoints.toTrackEnd() = StayDeriver.TrackEnd(
    trackId = id,
    startedAt = startedAt,
    endedAt = endedAt,
    start = if (startLat != null && startLon != null) Coordinate(startLat, startLon) else null,
    end = if (endLat != null && endLon != null) Coordinate(endLat, endLon) else null,
)

/**
 * Derives *stays* — where the user was between recorded tracks — from data the app already has, at
 * zero sensing cost: the interval between the end of one kept track and the start of the next,
 * when both endpoints land at "the same place". Same place means any of:
 *  - the same endpoint cluster ([PlaceClusterer] over every track endpoint in history, *seeded* by
 *    the named-place pins at each pin's own capture radius — widening a venue's radius is generous
 *    where blanket radii can't be; repeat visits widen organic clusters to the place's GPS scatter);
 *  - raw distance within [Params.agreementRadiusM], so nearby endpoints straddling two clusters
 *    still agree;
 *  - the same nearest *named place* pin within that pin's radius, for the residual case where a
 *    nearer organic anchor pulled one endpoint out of the pin's seeded cluster.
 * Endpoint disagreement means movement the recorder missed, reported as a [Gap] instead. The
 * endpoints alone decide: whether the app was watching in between is a fact about the app, not
 * about where anyone was, so an agreeing interval is a stay however the silence came about —
 * recorder off, app dead, or history imported from before the app existed. Pure and Android-free;
 * nothing is persisted — stays re-derive from the tracks on read, so history backfills
 * automatically and track deletions self-heal.
 *
 * **This rule is ported.** The web companion viewer derives the same timeline from a backup export
 * (`web/js/stays.js`, a port of this and [PlaceClusterer], tested case for case against
 * `StayDeriverTest`), so a rule that moves here moves there or the two disagree about the same
 * history. **[TimelineRows.slicePerDay] is the deliberate exception**: which clock a row is read on comes from
 * the bundled city atlas, and a backup file carries no zones, so the viewer reads every row on the
 * reader's own clock. That is a difference in *display*, not in which stays exist — everything
 * above this line is still one rule in two implementations.
 *
 * **However brief, a stop the endpoints agree on is a stay — there is no minimum duration here, and
 * a five-minute floor was tried and taken back out.** A stop is what a *place* accumulates visits
 * from, so suppressing the short ones costs the Places feature the recurring lunch stop and the
 * daily school run, which are exactly the spots worth naming. Every threshold therefore lives with
 * the reader that wants one, where it can be tuned against how that screen reads without changing
 * what the history is: [PlaceResolver.NOTABLE_VISIT_MIN] for which clusters a list surfaces,
 * [Stay.reportableDurationMs] for a duration worth printing, and [dayCategoryTotals]' own floor
 * for what earns a chip. A floor added back here would silently empty all three.
 */
object StayDeriver {

    /** One kept track, projected to what derivation needs. Input list must be ascending by time. */
    data class TrackEnd(
        val trackId: Long,
        val startedAt: Long,
        val endedAt: Long,
        /** First/last good-point coordinates; null only defensively (kept tracks have ≥2 points). */
        val start: Coordinate?,
        val end: Coordinate?,
    )

    data class Params(
        /** Fallback: endpoints at most this far apart (meters) agree even across cluster lines. */
        val agreementRadiusM: Double = 100.0,
        /** Radius for clustering track endpoints into places. */
        val placeRadiusM: Double = PlaceClusterer.DEFAULT_RADIUS_M,
        // No minimum stay duration belongs here — see the rule on [StayDeriver]. One was tried and
        // taken out, and adding it back empties the three reader-side floors that replaced it.
    )

    enum class GapReason { MOVED_UNRECORDED, UNKNOWN_ENDPOINT }

    sealed interface Interval {
        val start: Long
        val end: Long?

        /** The track this interval follows — one interval per track, so it identifies the
         *  interval itself, and survives the display slicing that rewrites the bounds. */
        val afterTrackId: Long
    }

    /**
     * Below this a stay's length is not worth reporting — it is not the length of anything the
     * user did. Measured between two track *bounds*, a stay covers only the part of a stop the
     * recorder noticed: the stationary approach usually sits untrimmed in the previous track's tail
     * whenever the stop was shorter than [EdgeStayDetector]'s dwell floor (history-wide, such stays
     * sit still for a median of ~2.5 min around a gap of seconds). Still a real stop — a visit,
     * kept on the timeline — but a duration from its bounds would be fiction, and rounding one to
     * "0m" reads as a broken value.
     */
    const val REPORTABLE_DURATION_MS = 60_000L

    data class Stay(
        override val start: Long,
        /** Null = ongoing (the current stay). */
        override val end: Long?,
        override val afterTrackId: Long,
        /**
         * Index into [Derivation.clusters] — the place this stay belongs to, and **the only answer
         * it has to where it was**. A stay is the interval two tracks leave between them, so where
         * is a question about the cluster its endpoints agreed on, whose centroid is the mean of
         * every visit rather than a point between one pair. The asymmetry with [Gap], which does
         * carry coordinates, is the point: a gap exists *because* its two ends disagree, so there
         * is no cluster to ask.
         */
        val clusterId: Int,
    ) : Interval {
        /** This stay's length when its own bounds are worth reporting as one, else null;
         *  [nowMs] measures an ongoing stay. See [REPORTABLE_DURATION_MS]. */
        fun reportableDurationMs(nowMs: Long): Long? =
            ((end ?: nowMs) - start).takeIf { it >= REPORTABLE_DURATION_MS }

        /**
         * No time in it at all — the seam two tracks sharing an instant leave behind, which is a
         * join between them rather than a stop. **The bare fact, owned here**, because more than one
         * reader turns it into a rule and each asks something slightly different of it: whether a
         * row is worth drawing ([TimelineItem.StayItem.isBareSeam]) or whether a visit happened
         * ([PlaceResolver]). Those questions may diverge; what "no duration" means may not.
         *
         * An ongoing stay is not one of these: no end is not an end equal to the start.
         */
        val hasNoDuration: Boolean get() = end == start
    }

    data class Gap(
        override val start: Long,
        override val end: Long,
        val reason: GapReason,
        override val afterTrackId: Long,
        /** Index into [Derivation.clusters] for each side (null = that endpoint is unknown) —
         *  most gaps are really one place misclustered as two, so the UI links each side to
         *  its place for fixing. */
        val fromClusterId: Int? = null,
        val toClusterId: Int? = null,
        /**
         * The two positions whose disagreement *is* this gap: where the recording left off and where
         * it picked up again, null on a side no fix was had for. The same coordinates the cluster
         * ids above were taken from, kept beside them rather than in their place — a cluster answers
         * "which place is this", these answer "where exactly was the phone at [start] and [end]",
         * and a trip entered by hand to fill the gap wants the second question: its two times are
         * these two instants, so these are where its ends were.
         */
        val from: Coordinate? = null,
        val to: Coordinate? = null,
    ) : Interval

    /** Derivation output: the timeline intervals plus the endpoint clusters stays index into. */
    data class Derivation(
        val intervals: List<Interval>,
        /** Clusters over every track endpoint — one per named-place pin first (in pin order,
         *  possibly memberless), then organic clusters chronologically; see [Stay.clusterId]. */
        val clusters: List<PlaceClusterer.Cluster>,
    )

    /**
     * Every interval **between two finished tracks**, and no other. The one that follows the newest
     * track is not a fact about a pair — it closes at the clock or at a recording track's start, both
     * of which move with no write behind them — so it is [tail]'s, and a caller that wants a whole
     * timeline appends it. Emitting it here as well would be a second author for the trailing stay,
     * and the one this pass produced would be stale by the time anything read it.
     */
    fun derive(
        tracks: List<TrackEnd>,
        params: Params = Params(),
        distance: DistanceFn,
        /** Named-place pins with their per-place capture radii: seed the endpoint clustering
         *  (in pin order — [PlaceResolver] maps [PlaceClusterer.Cluster.seedIndex] back to the
         *  same places list) and drive the same-nearest-pin agreement override. */
        placePins: List<PlaceClusterer.Seed> = emptyList(),
    ): Derivation {
        val (clusters, clusterOf) = clusterEndpoints(tracks, placePins, params, distance)
        val out = mutableListOf<Interval>()
        val agreement = Agreement(params, distance, placePins)

        for (i in 0 until tracks.size - 1) {
            val prev = tracks[i]
            val next = tracks[i + 1]
            val a = prev.end
            val b = next.start
            val aCluster = if (a != null) clusterOf.getValue(Endpoint(prev.trackId, isStart = false)) else null
            val bCluster = if (b != null) clusterOf.getValue(Endpoint(next.trackId, isStart = true)) else null
            val sameCluster = aCluster != null && aCluster == bCluster
            when (val verdict = verdictBetween(prev, next, sameCluster, agreement)) {
                Verdict.None -> Unit
                is Verdict.Moved -> out += Gap(
                    prev.endedAt, next.startedAt, verdict.reason,
                    afterTrackId = prev.trackId,
                    fromClusterId = aCluster,
                    toClusterId = bCluster,
                    from = a,
                    to = b,
                )
                Verdict.Stayed -> out += Stay(
                    start = prev.endedAt,
                    end = next.startedAt,
                    afterTrackId = prev.trackId,
                    clusterId = checkNotNull(aCluster) { "an agreeing pair has both ends" },
                )
            }
        }

        return Derivation(out, clusters)
    }

    /**
     * **When two adjacent endpoints are the same place** — the rule, stated once, for both the pass
     * that derives a whole history and the one that repairs a few rows of a stored derivation.
     * Two implementations of this test would be two answers to what a stay is.
     *
     * The ways to agree are tried in order of what they cost, not of what they mean: cluster
     * identity is already in hand, a distance is one call, and the shared-pin override is a scan of
     * the named pins.
     */
    class Agreement(
        private val params: Params,
        private val distance: DistanceFn,
        private val placePins: List<PlaceClusterer.Seed>,
    ) {
        fun samePlace(a: Coordinate, b: Coordinate, sameCluster: Boolean): Boolean =
            sameCluster ||
                distance.meters(a.lat, a.lon, b.lat, b.lon) <= params.agreementRadiusM ||
                (nearestPin(a)?.let { it == nearestPin(b) } ?: false)

        private fun nearestPin(e: Coordinate): Int? =
            PlaceClusterer.nearestSeedIndex(e.lat, e.lon, placePins, distance)
    }

    /**
     * What the interval between two adjacent kept tracks is — **without saying where**, which is the
     * one term the two passes that ask this represent differently: a derivation names a cluster by
     * its position in its own list, a repair by a row id or a row it is about to insert.
     *
     * Everything else about the decision lives here rather than in each of them: that a pair whose
     * clocks run backwards leaves nothing, that a *disagreeing* pair with no time in it is
     * meaningless and also leaves nothing, and which kind of gap a missing endpoint makes.
     */
    sealed interface Verdict {
        /** Nothing to record between these two tracks. */
        data object None : Verdict

        /** A verdict an interval can be built from — the whole of [Verdict] bar [None], so that a
         *  caller holding one of these has already ruled the empty case out and every writer of an
         *  interval row answers exhaustively over two cases rather than guarding a third at runtime. */
        sealed interface Recorded : Verdict

        data class Moved(val reason: GapReason) : Recorded

        data object Stayed : Recorded
    }

    internal fun verdictBetween(
        before: TrackEnd,
        after: TrackEnd,
        sameCluster: Boolean,
        agreement: Agreement,
    ): Verdict {
        val start = before.endedAt
        val end = after.startedAt
        // Negative gap (clock stepped backwards between tracks): emit nothing.
        if (end < start) return Verdict.None
        val a = before.end
        val b = after.start
        if (a == null || b == null || !agreement.samePlace(a, b, sameCluster)) {
            // A zero-length disagreement ("moved without recording, in zero time") is meaningless —
            // whereas a zero-length *agreeing* pair is a split seam (an edge-stay trim's cut), and
            // its stay carries the merge-back offer.
            if (end == start) return Verdict.None
            return Verdict.Moved(
                if (a == null || b == null) GapReason.UNKNOWN_ENDPOINT else GapReason.MOVED_UNRECORDED,
            )
        }
        return Verdict.Stayed
    }

    /**
     * Which end of which track — the identity an endpoint's cluster is held under, here and in the
     * ledger that repairs a stored derivation. Identity rather than position: two endpoints at one
     * coordinate can land in different clusters, since each joins the nearest anchor *reaching it*
     * and an anchor founded between them can be the nearer one for the second.
     */
    data class Endpoint(val trackId: Long, val isStart: Boolean)

    /** One endpoint the clustering reads: which track's, which of its two ends, when and where. */
    data class EndpointRef(
        val trackId: Long,
        val isStart: Boolean,
        val atMs: Long,
        val at: Coordinate,
    ) {
        val key: Endpoint get() = Endpoint(trackId, isStart)
    }

    /**
     * Every endpoint of [tracks], in the order the clustering reads them — each track's start then
     * its end, skipping an end the recorder never fixed.
     *
     * **The order is the contract**, and it is total. [PlaceClusterer.Cluster.memberIndices] index
     * into exactly this list, so a caller that wants to know *which* endpoints a cluster holds reads
     * them from here rather than rebuilding the order and hoping it still matches — and every index
     * a derivation produces is explained by an entry here, there being nothing else in what it
     * clusters.
     */
    fun endpointsOf(tracks: List<TrackEnd>): List<EndpointRef> = buildList {
        for (track in tracks) {
            track.start?.let { add(EndpointRef(track.trackId, true, track.startedAt, it)) }
            track.end?.let { add(EndpointRef(track.trackId, false, track.endedAt, it)) }
        }
    }

    /**
     * Clusters every track endpoint (chronological: each track's start then end) so anchors stay
     * stable as history grows; pin seeds put endpoints near a named place in its cluster. The map
     * answers by [Endpoint], the clustering's own member indices carried back to the endpoints they
     * were taken from.
     */
    private fun clusterEndpoints(
        tracks: List<TrackEnd>,
        placePins: List<PlaceClusterer.Seed>,
        params: Params,
        distance: DistanceFn,
    ): Pair<List<PlaceClusterer.Cluster>, Map<Endpoint, Int>> {
        val endpoints = endpointsOf(tracks)
        val clusters =
            PlaceClusterer.cluster(endpoints.map { it.at }, params.placeRadiusM, distance, seeds = placePins)
        val clusterOf = HashMap<Endpoint, Int>(endpoints.size)
        clusters.forEachIndexed { ci, cluster ->
            for (index in cluster.memberIndices) clusterOf[endpoints[index].key] = ci
        }
        return clusters to clusterOf
    }

    /**
     * What the trailing stay hangs off: the last kept track's id and end bound, and the cluster that
     * track ended in. One value rather than three arguments because it is one fact with one source —
     * the newest kept track's end endpoint — which is where the requirement that all three describe
     * the *same* track has somewhere to be stated.
     */
    data class TailAnchor(val trackId: Long, val endedAt: Long, val clusterId: Int)

    /**
     * The stay still running after the last kept track — where the user is now — closed at
     * [activeStartedAt] while a track is recording, so the just-ended stay reads live rather than
     * only once that track finalizes.
     *
     * **This interval cannot be snapshotted**, which is why it is reachable on its own: it is a
     * function of [nowMs] and of whether something is recording, and both move with no write behind
     * them. A cluster is all it needs of place, every other term being a bound.
     *
     * **It answers the agreeing case only.** Where a recording track's first fix disagrees with
     * where the last one ended, the trailing interval is movement the recorder missed — a [Gap] by
     * the same rule that governs two finished tracks — and telling the two apart needs both
     * coordinates, which this does not take. A caller that has them owes that check itself; what
     * follows from no caller having them is the reader's to state, not this leaf's.
     *
     * [disarmedSince] is when the recorder was last turned off with nothing since, or null while
     * armed: the app can attest nothing past a disarm, so the stay closes there rather than
     * stretching to the clock.
     */
    internal fun tail(
        anchor: TailAnchor,
        disarmedSince: Long?,
        nowMs: Long,
        activeStartedAt: Long?,
    ): Stay? {
        val start = anchor.endedAt
        val end = if (activeStartedAt != null) {
            if (activeStartedAt <= start) return null
            activeStartedAt
        } else {
            if (start > nowMs) return null
            disarmedSince?.coerceAtLeast(start)
        }
        return Stay(
            start = start,
            end = end,
            afterTrackId = anchor.trackId,
            clusterId = anchor.clusterId,
        )
    }
}
