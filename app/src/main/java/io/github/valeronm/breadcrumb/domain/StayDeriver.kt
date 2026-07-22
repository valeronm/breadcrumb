package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.DistanceFn
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import java.time.Instant
import java.time.ZoneId

/**
 * Derives *stays* — where the user was between recorded tracks — from data the app already has,
 * at zero sensing cost. A stay is the interval between the end of one kept track and the start of
 * the next, when both endpoints land at "the same place". Same place means any of:
 *  - the same endpoint cluster ([PlaceClusterer] over every track endpoint in history, *seeded*
 *    by the user's named-place pins at each pin's own capture radius — widening a venue's radius
 *    makes it generous where blanket radii can't be, and repeated visits widen organic clusters
 *    to the place's real GPS scatter);
 *  - raw distance within [Params.agreementRadiusM], so nearby endpoints straddling two clusters
 *    still agree;
 *  - the same nearest *named place* pin within that pin's radius, for the residual case where a
 *    nearer organic anchor pulled one endpoint out of the pin's seeded cluster.
 * Endpoint disagreement means movement the recorder missed, and is reported as a [Gap] instead.
 *
 * Honesty rule: silence is only a stay if the app was alive and armed throughout — that evidence
 * is the liveness log ([Armed]/[Disarmed]/[Outage] events). When an outage or a disarm-rearm
 * interrupts an otherwise-agreeing interval, the stay is still emitted but marked
 * [Provenance.INFERRED] (the endpoints agree; the middle is unattested) rather than
 * [Provenance.OBSERVED]. History from before the liveness log existed derives the same way.
 *
 * Pure and Android-free; nothing is persisted — stays re-derive from tracks + liveness on read,
 * so history backfills automatically and track deletions self-heal.
 */
object StayDeriver {

    data class Endpoint(val lat: Double, val lon: Double)

    /** One kept track, projected to what derivation needs. Input list must be ascending by time. */
    data class TrackEnd(
        val trackId: Long,
        val startedAt: Long,
        val endedAt: Long,
        /** First/last good-point coordinates; null only defensively (kept tracks have ≥2 points). */
        val start: Endpoint?,
        val end: Endpoint?,
    )

    /**
     * The currently-recording track. Its presence closes the tail stay at [startedAt] instead of
     * suppressing it, so the timeline shows the just-ended stay live rather than only after the
     * track finalizes. [start] is the track's first good fix when already known — it lets the
     * tail run the usual endpoint-agreement check; null (no fix yet) counts as agreement until
     * the finished track re-derives the interval for real.
     */
    data class ActiveTrack(val startedAt: Long, val start: Endpoint? = null)

    /** Recorder-lifecycle evidence, ascending by time. */
    sealed interface Liveness {
        val at: Long
    }

    data class Armed(override val at: Long) : Liveness
    data class Disarmed(override val at: Long) : Liveness

    /** The app was dead (or the phone off) from [at] to [until]. */
    data class Outage(override val at: Long, val until: Long) : Liveness

    data class Params(
        /** Fallback: endpoints at most this far apart (metres) agree even across cluster lines. */
        val agreementRadiusM: Double = 100.0,
        /** Radius for clustering track endpoints into places. */
        val placeRadiusM: Double = PlaceClusterer.DEFAULT_RADIUS_M,
        /** Inter-track gaps shorter than this emit nothing. 0 = keep every stay (brief stops
         *  included) — the auto-pause resume window already absorbs the truly-momentary ones. */
        val minStayMs: Long = 0L,
        /** Heartbeat staleness after which a restart materializes an outage. Lives here as the
         *  single source of truth; the service reads it when calling materializeOutageIfDead. */
        val heartbeatToleranceMs: Long = 30 * 60_000L,
    )

    enum class Provenance { OBSERVED, INFERRED }

    enum class GapReason { MOVED_UNRECORDED, UNKNOWN_ENDPOINT }

    sealed interface Interval {
        val start: Long
        val end: Long?
    }

    /**
     * Below this a stay's length is not worth reporting, because it is not the length of anything
     * the user did. A stay is measured between two track *bounds*, so it only covers the part of
     * a stop the recorder noticed: the stationary approach usually sits untrimmed inside the
     * previous track's tail whenever the stop was shorter than [EdgeStayDetector]'s dwell floor
     * (measured over the whole history, these short stays sit still for a median of ~2.5 min
     * around a gap of seconds). Such a stay is still a real stop — it counts as a visit and keeps
     * its place on the timeline — but any duration derived from its bounds would be fiction, and
     * rounding one to "0m" reads as a broken value.
     *
     * [TrackMerge] uses the same line for the one decision that turns on it: whether a stay on a
     * named place is substantial enough that merging it away would delete a real visit.
     */
    const val REPORTABLE_DURATION_MS = 60_000L

    data class Stay(
        override val start: Long,
        /** Null = ongoing (the current stay). */
        override val end: Long?,
        val location: Endpoint,
        val provenance: Provenance,
        /** The track whose end anchors this stay. */
        val afterTrackId: Long,
        /** Index into [Derivation.clusters] — the place this stay belongs to. */
        val clusterId: Int,
    ) : Interval {
        /** This stay's length when its own bounds are worth reporting as one, else null;
         *  [nowMs] measures an ongoing stay. See [REPORTABLE_DURATION_MS]. */
        fun reportableDurationMs(nowMs: Long): Long? =
            ((end ?: nowMs) - start).takeIf { it >= REPORTABLE_DURATION_MS }
    }

    data class Gap(
        override val start: Long,
        override val end: Long,
        val reason: GapReason,
        /** Index into [Derivation.clusters] for each side (null = that endpoint is unknown) —
         *  most gaps are really one place misclustered as two, so the UI links each side to
         *  its place for fixing. */
        val fromClusterId: Int? = null,
        val toClusterId: Int? = null,
    ) : Interval

    /** Derivation output: the timeline intervals plus the endpoint clusters stays index into. */
    data class Derivation(
        val intervals: List<Interval>,
        /** Clusters over every track endpoint — one per named-place pin first (in pin order,
         *  possibly memberless), then organic clusters chronologically; see [Stay.clusterId]. */
        val clusters: List<PlaceClusterer.Cluster>,
    )

    fun derive(
        tracks: List<TrackEnd>,
        liveness: List<Liveness>,
        nowMs: Long,
        activeTrack: ActiveTrack?,
        params: Params = Params(),
        distance: DistanceFn,
        /** Named-place pins with their per-place capture radii: seed the endpoint clustering
         *  (in pin order — [PlaceResolver] maps [PlaceClusterer.Cluster.seedIndex] back to the
         *  same places list) and drive the same-nearest-pin agreement override. */
        placePins: List<PlaceClusterer.Seed> = emptyList(),
    ): Derivation {
        val evidence = summarizeLiveness(liveness, nowMs)
        val (clusters, clusterOf) = clusterEndpoints(tracks, activeTrack?.start, placePins, params, distance)
        val out = mutableListOf<Interval>()

        fun nearestPin(e: Endpoint): Int? = placePins.indices
            .map { it to distance.metres(placePins[it].anchor.lat, placePins[it].anchor.lon, e.lat, e.lon) }
            .filter { (i, d) -> d <= placePins[i].radiusM }
            .minByOrNull { (_, d) -> d }
            ?.first

        fun samePlace(a: Endpoint, b: Endpoint): Boolean =
            clusterOf.getValue(a) == clusterOf.getValue(b) ||
                distance.metres(a.lat, a.lon, b.lat, b.lon) <= params.agreementRadiusM ||
                (nearestPin(a)?.let { it == nearestPin(b) } ?: false)

        for (i in 0 until tracks.size - 1) {
            val prev = tracks[i]
            val next = tracks[i + 1]
            val gapStart = prev.endedAt
            val gapEnd = next.startedAt
            // Negative gap (clock stepped backwards between tracks): emit nothing.
            if (gapEnd < gapStart) continue
            val a = prev.end
            val b = next.start
            if (a == null || b == null || !samePlace(a, b)) {
                // A zero-length disagreement ("moved without recording, in zero time") is
                // meaningless — whereas a zero-length *agreeing* gap below is a split seam (an
                // edge-stay trim's cut), and its stay carries the merge-back offer.
                if (gapEnd == gapStart) continue
                val reason = if (a == null || b == null) GapReason.UNKNOWN_ENDPOINT
                else GapReason.MOVED_UNRECORDED
                out += Gap(
                    gapStart, gapEnd, reason,
                    fromClusterId = a?.let(clusterOf::getValue),
                    toClusterId = b?.let(clusterOf::getValue),
                )
                continue
            }
            if (gapEnd - gapStart < params.minStayMs) continue
            out += Stay(
                start = gapStart,
                end = gapEnd,
                location = midpoint(a, b),
                provenance = evidence.provenanceOver(gapStart, gapEnd),
                afterTrackId = prev.trackId,
                clusterId = clusterOf.getValue(a),
            )
        }

        tailStay(tracks.lastOrNull(), evidence, nowMs, activeTrack, params, clusterOf, ::samePlace)
            ?.let { out += it }
        return Derivation(out, clusters)
    }

    /**
     * Clusters every track endpoint (chronological: each track's start then end) so anchors are
     * stable as history grows, seeding the clustering with the named-place pins so endpoints near
     * a pin belong to that place's cluster. Identical coordinates always land in the same cluster,
     * so the value-keyed map is safe even when endpoints repeat.
     */
    private fun clusterEndpoints(
        tracks: List<TrackEnd>,
        activeStart: Endpoint?,
        placePins: List<PlaceClusterer.Seed>,
        params: Params,
        distance: DistanceFn,
    ): Pair<List<PlaceClusterer.Cluster>, Map<Endpoint, Int>> {
        val endpoints = buildList {
            for (track in tracks) {
                track.start?.let { add(it) }
                track.end?.let { add(it) }
            }
            // The active track's first fix joins the clustering so the tail's agreement check
            // can use cluster identity like every other pair.
            activeStart?.let { add(it) }
        }
        val clusters = PlaceClusterer.cluster(endpoints, params.placeRadiusM, distance, seeds = placePins)
        val clusterOf = HashMap<Endpoint, Int>(endpoints.size)
        clusters.forEachIndexed { ci, cluster ->
            for (index in cluster.memberIndices) clusterOf[endpoints[index]] = ci
        }
        return clusters to clusterOf
    }

    /**
     * The stay after the last finished track: open-ended while idle (where the user is right
     * now), closed at the active track's start while recording — so the timeline shows the
     * just-ended stay live instead of only after the track finalizes.
     */
    private fun tailStay(
        last: TrackEnd?,
        evidence: LivenessSummary,
        nowMs: Long,
        activeTrack: ActiveTrack?,
        params: Params,
        clusterOf: Map<Endpoint, Int>,
        samePlace: (Endpoint, Endpoint) -> Boolean,
    ): Interval? {
        if (last == null) return null
        val location = last.end ?: return null
        val start = last.endedAt
        if (activeTrack != null) {
            val end = activeTrack.startedAt
            if (end <= start) return null
            // A known first fix that disagrees means the recorder missed movement — same rule
            // as between finished tracks. No fix yet counts as agreement; the interval
            // re-derives for real once the track finishes.
            val b = activeTrack.start
            if (b != null && !samePlace(location, b)) {
                return Gap(
                    start, end, GapReason.MOVED_UNRECORDED,
                    fromClusterId = clusterOf.getValue(location),
                    toClusterId = clusterOf.getValue(b),
                )
            }
            if (end - start < params.minStayMs) return null
            return Stay(
                start = start,
                end = end,
                location = location,
                provenance = evidence.provenanceOver(start, end),
                afterTrackId = last.trackId,
                clusterId = clusterOf.getValue(location),
            )
        }
        if (start > nowMs) return null
        // If currently disarmed, the app can attest nothing past the disarm — close the stay there.
        val end = evidence.disarmedSince?.coerceAtLeast(start)
        val effectiveEnd = end ?: nowMs
        if (effectiveEnd - start < params.minStayMs) return null
        return Stay(
            start = start,
            end = end,
            location = location,
            provenance = evidence.provenanceOver(start, effectiveEnd),
            afterTrackId = last.trackId,
            clusterId = clusterOf.getValue(location),
        )
    }

    private fun midpoint(a: Endpoint, b: Endpoint) =
        Endpoint((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)

    // --- Liveness evidence ----------------------------------------------------

    private class LivenessSummary(
        /** Half-open [start, end) intervals where the app was known dead or disarmed. */
        val deadIntervals: List<Pair<Long, Long>>,
        /** Time of the earliest liveness evidence; anything before it is unattested. */
        val firstEvidenceAt: Long?,
        /** Set when the latest state is "disarmed with no re-arm" — dead from here on. */
        val disarmedSince: Long?,
    ) {
        fun provenanceOver(start: Long, end: Long): Provenance = when {
            firstEvidenceAt == null || start < firstEvidenceAt -> Provenance.INFERRED
            deadIntervals.any { (ds, de) -> ds < end && start < de } -> Provenance.INFERRED
            else -> Provenance.OBSERVED
        }
    }

    private fun summarizeLiveness(liveness: List<Liveness>, nowMs: Long): LivenessSummary {
        val dead = mutableListOf<Pair<Long, Long>>()
        var disarmedSince: Long? = null
        for (event in liveness) {
            when (event) {
                is Outage -> dead += event.at.coerceAtMost(nowMs) to event.until.coerceAtMost(nowMs)
                is Disarmed -> if (disarmedSince == null) disarmedSince = event.at.coerceAtMost(nowMs)
                is Armed -> {
                    disarmedSince?.let { dead += it to event.at.coerceAtMost(nowMs) }
                    disarmedSince = null
                }
            }
        }
        // A trailing disarm is dead through "now" for mid-list gaps; the tail stay handles it
        // explicitly via disarmedSince.
        disarmedSince?.let { dead += it to nowMs }
        return LivenessSummary(
            deadIntervals = dead,
            firstEvidenceAt = liveness.firstOrNull()?.at?.coerceAtMost(nowMs),
            disarmedSince = disarmedSince,
        )
    }

    // --- Display helpers -------------------------------------------------------

    /**
     * Splits intervals at local midnights so each piece falls inside one calendar day (a
     * 20:00–09:00 stay renders in both days with clamped bounds). An ongoing stay keeps its null
     * end on the final (today's) slice.
     */
    fun slicePerDay(intervals: List<Interval>, zone: ZoneId, nowMs: Long): List<Interval> =
        intervals.flatMap { interval ->
            val end = interval.end ?: nowMs
            val slices = mutableListOf<Interval>()
            var sliceStart = interval.start
            while (true) {
                val nextMidnight = Instant.ofEpochMilli(sliceStart).atZone(zone)
                    .toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                if (end <= nextMidnight) {
                    slices += copyWith(interval, sliceStart, interval.end)
                    break
                }
                slices += copyWith(interval, sliceStart, nextMidnight)
                sliceStart = nextMidnight
            }
            slices
        }

    private fun copyWith(interval: Interval, start: Long, end: Long?): Interval = when (interval) {
        is Stay -> interval.copy(start = start, end = end)
        is Gap -> interval.copy(start = start, end = requireNotNull(end))
    }

    /**
     * Merges the DESC track list with derived intervals into one DESC timeline. On a start-time
     * tie the interval sorts newer (it extends past the shared instant; the track ended at it).
     */
    fun interleave(summaries: List<TrackSummary>, intervals: List<Interval>): List<TimelineItem> {
        val descIntervals = intervals.asReversed()
        val out = ArrayList<TimelineItem>(summaries.size + intervals.size)
        var t = 0
        var v = 0
        while (t < summaries.size || v < descIntervals.size) {
            val track = summaries.getOrNull(t)
            val interval = descIntervals.getOrNull(v)
            val takeInterval = when {
                interval == null -> false
                track == null -> true
                interval.start != track.startedAt -> interval.start > track.startedAt
                // Start-time tie: an ongoing interval is the newest thing on the timeline, but a
                // closed one ended the instant this track began (a zero-length trim seam), so the
                // departing track is newer and the interval sorts between the two tracks.
                else -> interval.end == null
            }
            if (takeInterval) {
                out += when (val iv = descIntervals[v++]) {
                    is Stay -> TimelineItem.StayItem(iv)
                    is Gap -> TimelineItem.GapItem(iv)
                }
            } else {
                out += TimelineItem.TrackItem(summaries[t++])
            }
        }
        return out
    }
}

/** One row of the day-grouped timeline: a recorded track, a derived stay, or a data gap. */
sealed interface TimelineItem {
    val startedAt: Long

    data class TrackItem(val summary: TrackSummary) : TimelineItem {
        override val startedAt get() = summary.startedAt
    }

    data class StayItem(
        val stay: StayDeriver.Stay,
        /** Place resolution, attached after derivation; null only if resolution wasn't run. */
        val place: PlaceResolver.ResolvedStay? = null,
        /** Non-null when this short same-activity stay can be closed by merging its two tracks. */
        val merge: TrackMerge.Plan? = null,
    ) : TimelineItem {
        override val startedAt get() = stay.start
    }

    data class GapItem(
        val gap: StayDeriver.Gap,
        /** Place resolution of each known side, attached after derivation — lets the row link
         *  through to the places whose misclustering usually caused the gap. */
        val fromPlace: PlaceResolver.ResolvedStay? = null,
        val toPlace: PlaceResolver.ResolvedStay? = null,
    ) : TimelineItem {
        override val startedAt get() = gap.start
    }
}
