package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary
import java.time.Instant
import java.time.ZoneId

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
 * Endpoint disagreement means movement the recorder missed, reported as a [Gap] instead. Honesty
 * rule: silence is only a stay if the app was alive and armed throughout, per the liveness log
 * ([Armed]/[Disarmed]/[Outage] events); an outage or disarm-rearm interrupting an otherwise-agreeing
 * interval still emits the stay, marked [Provenance.INFERRED] (the endpoints agree; the middle is
 * unattested) rather than [Provenance.OBSERVED], and pre-log history derives the same way. Pure and
 * Android-free; nothing is persisted — stays re-derive from tracks + liveness on read, so history
 * backfills automatically and track deletions self-heal.
 *
 * **This rule is ported.** The web companion viewer derives the same timeline from a backup export
 * (`web/js/stays.js`, a port of this and [PlaceClusterer], tested case for case against
 * `StayDeriverTest`), so a rule that moves here moves there or the two disagree about the same
 * history. **[slicePerDay] is the deliberate exception**: which clock a row is read on comes from
 * the bundled gazetteer, and a backup file carries no zones, so the viewer reads every row on the
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
     * The currently-recording track: its presence closes the tail stay at [startedAt] instead of
     * suppressing it, so the just-ended stay shows live rather than only after the track finalizes.
     * [start], the first good fix when already known, lets the tail run the usual endpoint-agreement
     * check; null (no fix yet) counts as agreement until the finished track re-derives for real.
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
        /** Fallback: endpoints at most this far apart (meters) agree even across cluster lines. */
        val agreementRadiusM: Double = 100.0,
        /** Radius for clustering track endpoints into places. */
        val placeRadiusM: Double = PlaceClusterer.DEFAULT_RADIUS_M,
        // No minimum stay duration belongs here — see the rule on [StayDeriver]. One was tried and
        // taken out, and adding it back empties the three reader-side floors that replaced it.
        /** Heartbeat staleness after which a restart materializes an outage — the single source
         *  of truth for that threshold. */
        val heartbeatToleranceMs: Long = 30 * 60_000L,
    )

    enum class Provenance { OBSERVED, INFERRED }

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
        val location: Endpoint,
        val provenance: Provenance,
        override val afterTrackId: Long,
        /** Index into [Derivation.clusters] — the place this stay belongs to. */
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
        val from: Endpoint? = null,
        val to: Endpoint? = null,
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

        fun nearestPin(e: Endpoint): Int? =
            PlaceClusterer.nearestSeedIndex(e.lat, e.lon, placePins, distance)

        fun samePlace(a: Endpoint, b: Endpoint): Boolean =
            clusterOf.getValue(a) == clusterOf.getValue(b) ||
                distance.meters(a.lat, a.lon, b.lat, b.lon) <= params.agreementRadiusM ||
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
                val reason = if (a == null || b == null) {
                    GapReason.UNKNOWN_ENDPOINT
                } else {
                    GapReason.MOVED_UNRECORDED
                }
                out += Gap(
                    gapStart, gapEnd, reason,
                    afterTrackId = prev.trackId,
                    fromClusterId = a?.let(clusterOf::getValue),
                    toClusterId = b?.let(clusterOf::getValue),
                    from = a,
                    to = b,
                )
                continue
            }
            out += Stay(
                start = gapStart,
                end = gapEnd,
                location = midpoint(a, b),
                provenance = evidence.provenanceOver(gapStart, gapEnd),
                afterTrackId = prev.trackId,
                clusterId = clusterOf.getValue(a),
            )
        }

        tailStay(tracks.lastOrNull(), evidence, nowMs, activeTrack, clusterOf, ::samePlace)
            ?.let { out += it }
        return Derivation(out, clusters)
    }

    /**
     * Clusters every track endpoint (chronological: each track's start then end) so anchors stay
     * stable as history grows; pin seeds put endpoints near a named place in its cluster. Identical
     * coordinates always land in the same cluster, so the value-keyed map is safe under repeats.
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
                    afterTrackId = last.trackId,
                    fromClusterId = clusterOf.getValue(location),
                    toClusterId = clusterOf.getValue(b),
                    from = location,
                    to = b,
                )
            }
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
     * Splits **stays** at local midnights so each piece falls inside one calendar day (a
     * 20:00–09:00 stay renders in both days with clamped bounds). An ongoing stay keeps its null
     * end on the final (today's) slice.
     *
     * **A gap is cut once and never per day**, and the asymmetry is the point: a stay says something
     * true about each day it covers — where someone was — so a day is entitled to its own piece of
     * one. A gap says nothing about the days in the middle of it, and cutting per day gave those days
     * a row holding no time, no place and no end to offer the trip form, whose whole content was that
     * nothing is known. Its two *ends* are real, though, and each belongs to its own day: one cut, at
     * the local midnight opening the day it ended, gives the departure day a row saying when
     * recording stopped and the arrival day one saying when it resumed. The days between are folded
     * into the departure half and get no rows, which is what an unrecorded day is; how long the
     * absence ran is then read off the two day headings, which is where a reader looks anyway.
     *
     * **[zonesOf] answers per interval, not once**, because a midnight is a fact about where someone
     * was and not about the device reading the history back: a stay abroad ends its day on that
     * country's clock. A caller that hands back one constant zone for everything gets the whole
     * history cut on that clock, which is the old behaviour exactly.
     *
     * It answers with the clocks the interval's **two ends** ran on, and they are usually the same
     * one — every stay sits in a single place, which is what makes its own midnights the right cut.
     *
     * Where they differ the interval is an unrecorded **crossing**, and the single cut matters for a
     * second reason: cutting it at either end's midnights throughout would file the same absence
     * under two headings, once on the calendar the departure's clock was keeping and once on the
     * arrival's, and no choice of which end to file by removes that.
     *
     * Every reader of a bound this produced must ask [zonesOf] the same question about the same
     * interval, or a row will contradict the day it was filed under.
     */
    fun slicePerDay(
        intervals: List<Interval>,
        zonesOf: (Interval) -> Pair<ZoneId, ZoneId>,
        nowMs: Long,
    ): List<Slice> =
        intervals.flatMap { interval ->
            val (zone, endZone) = zonesOf(interval)
            // One rule per kind, which is the whole of the asymmetry this doc argues for. A stay's
            // two ends always answer with the same clock ([zonesOf]), so it never reaches the halving.
            when (interval) {
                is Gap -> halvesAtArrivalDay(interval, zone, endZone)
                is Stay -> daySlicesOf(interval, zone, nowMs)
            }
        }

    /**
     * An absence as its two halves — everything up to the arrival's local midnight, and the arrival's
     * own day. One piece when the whole of it already sits inside that day, which is the ordinary
     * short outage: there is no second day to give a row to, so one piece speaks for both ends.
     *
     * Whole days in the middle are folded into the departure half rather than marked one by one:
     * they hold neither end, and a row for one could only say that nothing is known.
     *
     * Each half is stamped with the end it speaks for and null for the other, at the point of
     * cutting — the only place that knows. A caller recovering it afterwards by comparing bounds
     * would be re-deciding what was decided here, and would have to be corrected in step with any
     * change to where the cut lands.
     */
    private fun halvesAtArrivalDay(gap: Gap, departureZone: ZoneId, arrivalZone: ZoneId): List<Slice> {
        val arrivalDayStart = startOfDay(gap.end, arrivalZone)
        if (arrivalDayStart <= gap.start) return listOf(Slice(gap, departureZone, arrivalZone))
        return listOf(
            Slice(gap.copy(end = arrivalDayStart), departureZone, null, holdsEnd = false),
            Slice(gap.copy(start = arrivalDayStart), null, arrivalZone, holdsStart = false),
        )
    }

    /** A stay as one piece per calendar day it covers, on its own [zone]; an ongoing one keeps its
     *  null end on the final piece, which is today's. Each piece says which of its bounds are the
     *  stay's own and which the slicing put there — see [Slice.holdsStart]. */
    private fun daySlicesOf(stay: Stay, zone: ZoneId, nowMs: Long): List<Slice> {
        val end = stay.end ?: nowMs
        val slices = mutableListOf<Slice>()
        var sliceStart = stay.start
        while (true) {
            val first = sliceStart == stay.start
            val nextMidnight = midnightAfter(sliceStart, zone)
            if (end <= nextMidnight) {
                slices += Slice(stay.copy(start = sliceStart), zone, zone, holdsStart = first)
                break
            }
            slices += Slice(
                stay.copy(start = sliceStart, end = nextMidnight),
                zone,
                zone,
                holdsStart = first,
                holdsEnd = false,
            )
            sliceStart = nextMidnight
        }
        return slices
    }

    /** Local midnight opening the day [epochMs] falls in. The one arithmetic here that DST rides
     *  on, so it is written once and [midnightAfter] is the only variant. */
    private fun startOfDay(epochMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().atStartOfDay(zone)
            .toInstant().toEpochMilli()

    /** Local midnight closing the day [epochMs] falls in — see [startOfDay]. */
    private fun midnightAfter(epochMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone)
            .toInstant().toEpochMilli()

    /**
     * Merges the DESC track list with derived intervals into one DESC timeline. On a start-time
     * tie the interval sorts newer (it extends past the shared instant; the track ended at it).
     */
    fun interleave(summaries: List<TrackSummary>, slices: List<Slice>): List<TimelineItem> {
        val descSlices = slices.asReversed()
        val out = ArrayList<TimelineItem>(summaries.size + slices.size)
        var t = 0
        var v = 0
        while (t < summaries.size || v < descSlices.size) {
            val track = summaries.getOrNull(t)
            val interval = descSlices.getOrNull(v)?.interval
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
                val slice = descSlices[v++]
                out += when (val iv = slice.interval) {
                    // A stay sits in one place, so its two ends answer with the same clock and the
                    // slicer set both; either reads it.
                    is Stay -> TimelineItem.StayItem(
                        iv,
                        zone = slice.departureZone,
                        holdsStart = slice.holdsStart,
                        holdsEnd = slice.holdsEnd,
                    )
                    is Gap -> TimelineItem.GapItem(
                        iv,
                        departureZone = slice.departureZone,
                        arrivalZone = slice.arrivalZone,
                        holdsDeparture = slice.holdsStart,
                        holdsArrival = slice.holdsEnd,
                    )
                }
            } else {
                out += TimelineItem.TrackItem(summaries[t++])
            }
        }
        return out
    }

    /**
     * One row's worth of an interval, with the clock each of its ends runs on.
     *
     * **Null means this piece does not speak for that end** — a crossing's departure half carries no
     * arrival clock, because the arrival is a row of its own under its own day's heading. Everything
     * that happens in one place carries the same clock twice.
     */
    data class Slice(
        val interval: Interval,
        val departureZone: ZoneId?,
        val arrivalZone: ZoneId?,
        /**
         * Whether this piece's own bounds are the interval's own, or boundaries the day slicing put
         * there — **stamped here, at the only point that knows**, and read by both kinds of row.
         *
         * A reader deciding it instead by comparing a bound against local midnight cannot tell an
         * interval that genuinely begins at 00:00 from one cut there, and the add-trip form produces
         * exactly that: its pickers resolve to the minute, so a trip typed to arrive at midnight
         * lands on the boundary and the stay after it would lose its start time and its duration.
         */
        val holdsStart: Boolean = true,
        val holdsEnd: Boolean = true,
    )
}

/** One row of the day-grouped timeline: a recorded track, a derived stay, or a data gap. */
sealed interface TimelineItem {
    val startedAt: Long

    /**
     * The instant deciding which day this row is filed under — its start, and its start for
     * everything that happens in one place. A row spanning two calendars overrides it; see
     * [GapItem.filedAt] for why an arrival wins over a departure there.
     *
     * Sorting is still by [startedAt]: this moves a row's *heading*, never its position.
     */
    val filedAt: Long get() = startedAt

    /**
     * The clock this row ran on — the zone of the place it happened in, attached after derivation
     * beside the place resolution; null only if attachment wasn't run.
     *
     * **It must be the zone [StayDeriver.slicePerDay] cut this row's bounds in.** A bound clamped to
     * one midnight and read back against another is a row contradicting the day it is filed under,
     * which is the whole reason the zone rides the row rather than being looked up per reader.
     */
    val zone: ZoneId?

    data class TrackItem(
        val summary: TrackSummary,
        /** Where it set off — the clock it is filed and starts on. */
        override val zone: ZoneId? = null,
        /**
         * Where it arrived. **A track is the one recorded row that can cross a border** — a stay
         * sits in one place by definition — so its two ends are read on their own clocks and the
         * crossing shows, rather than being flattened onto the departure's and hidden.
         *
         * Null means *unattached*, exactly as [zone] does, and never "the same as [zone]": a track
         * that began and ended on one clock carries that clock twice. Encoding sameness as null
         * would give the word a second meaning here that it does not have on [GapItem], forty lines
         * down, where null means the row does not speak for that end at all.
         */
        val endZone: ZoneId? = null,
    ) : TimelineItem {
        override val startedAt get() = summary.startedAt
    }

    data class StayItem(
        val stay: StayDeriver.Stay,
        /** Place resolution, attached after derivation; null only if resolution wasn't run. */
        val place: PlaceResolver.ResolvedStay? = null,
        /** Non-null when this short same-activity stay can be closed by merging its two tracks. */
        val merge: TrackMerge.Plan? = null,
        override val zone: ZoneId? = null,
        /**
         * Whether this row's bounds are the stay's own or seams the day slicing put there — see
         * [StayDeriver.Slice.holdsStart], which stamps them. A row states only the bounds it holds,
         * and only a row holding both can say how long the stay lasted.
         */
        val holdsStart: Boolean = true,
        val holdsEnd: Boolean = true,
    ) : TimelineItem {
        override val startedAt get() = stay.start

        /**
         * A row about a *join* rather than about being anywhere: two tracks sharing an instant leave
         * a stay of no duration between them — a split's cut, or a hand-entered trip landing exactly
         * on the absence it fills — and this one has nothing to say about it.
         *
         * Such a seam earns its row only while it carries the offer to undo the join, which is why
         * this asks after [merge] rather than after the writers either side: refusing across writers
         * is one of three things [TrackMerge.plan] refuses on, and a seam whose offer was refused for
         * either of the others says exactly as little.
         *
         * The derivation still emits every seam — this is the timeline deciding what to draw, and
         * the readers below it decide separately. [PlaceResolver] asks its own, narrower question of
         * the same intervals (was anyone anywhere, rather than is this row worth a line), so a seam
         * counts as no visit there whether or not a merge is offered here.
         */
        val isBareSeam: Boolean get() = merge == null && stay.hasNoDuration
    }

    data class GapItem(
        val gap: StayDeriver.Gap,
        /** Place resolution of each known side, attached after derivation — lets the row link
         *  through to the places whose misclustering usually caused the gap. */
        val fromPlace: PlaceResolver.ResolvedStay? = null,
        val toPlace: PlaceResolver.ResolvedStay? = null,
        /** Non-null when this short same-activity gap can be closed by merging its two tracks. */
        val merge: TrackMerge.Plan? = null,
        /**
         * The clocks this row's two ends run on, **null where the row does not speak for that end**
         * — see [StayDeriver.Slice], which sets them.
         *
         * Any absence longer than the day it ended on is cut into the day it left and the day it
         * landed, one end each. Three states, and every one is read off these two: a row holding
         * both ends carries a clock twice (the absence began and ended on one day); a departure half
         * carries no arrival clock; an arrival half no departure clock. Both null is the state
         * nothing produces — see [holdsDeparture].
         */
        val departureZone: ZoneId? = null,
        val arrivalZone: ZoneId? = null,
        /**
         * Whether this row states the absence's real start — and [holdsArrival] its real end. **The
         * two questions the whole card is drawn from**, stamped by the slicer when it cut
         * ([StayDeriver.Slice.holdsStart]) and read here; everything else about the row follows.
         *
         * At least one is always true: a piece holding neither would be a day the absence merely
         * passes through, and those are folded into the departure half rather than given a row.
         */
        val holdsDeparture: Boolean = true,
        /** See [holdsDeparture]. */
        val holdsArrival: Boolean = true,
    ) : TimelineItem {
        override val startedAt get() = gap.start

        /** The clock the row is read and filed on: the end it arrives at, or its departure where it
         *  speaks for no arrival. */
        override val zone get() = if (holdsArrival) arrivalZone else departureZone

        /** True where the two ends run on genuinely different clocks. Only a row holding *both* can
         *  say so, which after the cut means an absence that ended on the day it began — a hop, and
         *  the only row that has to state two times of its own. */
        val spansClocks get() = holdsDeparture &&
            holdsArrival &&
            departureZone != arrivalZone

        /**
         * **An arrival half is filed under the day it landed**; everything else by where it began,
         * like every other row.
         *
         * The row following an arrival is whatever was recorded on getting there — the taxi from the
         * airport — and it belongs to the arrival's day. File the arrival half by where it *began*
         * and a heading lands between the two, leaving an arrival at 19:16 under yesterday while the
         * 19:16 taxi sits under today. Nothing can sort between a gap's ends, there being no
         * recording in it, so this moves a heading and never the order.
         *
         * A row holding the departure must **not** take this: it would file an absence under the day
         * it finished and leave the day it began showing nothing at all.
         *
         * The cut currently puts an arrival half's start *at* that day's midnight, so the two agree
         * and this is belt and braces. It says which end the row is filed by, and goes on saying it
         * if the cut ever lands elsewhere.
         */
        override val filedAt get() = if (holdsArrival && !holdsDeparture) gap.end else gap.start
    }
}
