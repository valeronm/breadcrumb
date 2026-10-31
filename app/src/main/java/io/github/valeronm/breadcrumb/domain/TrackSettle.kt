package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * How the verdicts that take good fixes off a track's path are recorded — [EdgeStayDetector]'s
 * overrun as [IgnoreReason.EDGE_STAY], [StationaryRuns]' stops as [IgnoreReason.STATIONARY] — and,
 * through [settle], which every writer enters by, where a track's clock is read once they have been.
 * The flagged fixes stay on the track: not bad fixes but good ones that are not the journey's path,
 * and "ignored" is exactly the status they need, which the app already has: ignored points keep
 * their rows while dropping out of distance, the rendered line, the endpoints, and GPX export.
 * Nothing is moved to another track and nothing is deleted, so the operation is undone by clearing a
 * flag. Two rules make that safe to apply automatically and re-apply forever:
 *  1. **Detection never sees its own output.** [plan] hands the detectors the points with the
 *     pass's flags cleared, so every verdict is derived from the raw recording — fed the shortened
 *     track instead, the overrun rule would find a fresh stay inside the remainder each time and walk
 *     the track backwards, one sweep at a time.
 *  2. **A flag survives only where a rule still re-derives it.** Every flag is reconsidered,
 *     wherever it sits, and one no detector finds again is withdrawn. [TrackMerge] copies two tracks
 *     into one, putting the earlier track's flagged tail mid-track where an edge rule will never
 *     re-derive it, so it is handed back: those fixes are the merged track's own path through a stop
 *     it drove on from, and holding the buried flag because nothing re-examines it would keep them
 *     off the line on the say-so of a rule that stopped applying — a gap no map layer drew and no
 *     legend named.
 * Pure and Android-free.
 */
object TrackSettle {

    /**
     * Bumped whenever [settle] would settle a track differently — a new stage, a moved threshold in
     * any rule it runs, a different boundary, or a change to the clock [TrackBounds] reads off the
     * flags. The flags on stored points and the bounds over them are this
     * pass's verdicts, so a moved rule leaves them stale in both directions; the app re-sweeps its
     * history when the version it last swept is behind this one, making the bump part of changing
     * the rule, not a follow-up chore. One version for the one pass: a second keyed to the same sweep
     * would only be a second thing to forget.
     * 1 — the original half-minute edge-stay sweep.
     * 2 — displacement vetoes Doppler, real-fix boundary, dwell retraction, per-bin voting, vehicle standstill floor.
     * 3 — no rule change (a bump taken to watch the sweep run).
     * 4 — the per-bin vote floor scales with the track's own cadence, so bin-scale sampling is detectable at all.
     * 5 — no rule change: the verdict moved from a review mark onto the points; history re-swept to acquire it.
     * 6 — no rule change: every flag is now reconsidered wherever it sits, so a merge-buried overrun is handed back.
     * 7 — no detection change: the clock follows the good fixes ([TrackBounds]), covering the edge
     *     this rule declines — an arrival GPS went blind for leaves nothing to place a boundary in.
     * 8 — [StationaryRuns] thins the inside of every stop.
     * 9 — a stop ends where the altitude leaves its first fix's level ([StationaryRuns.sameLevel]).
     */
    const val RULE_VERSION = 9

    /**
     * What a track's rows should read once the current rules have had their say: which points gain
     * a flag and which lose one. Empty index sets mean the stored state already agrees — the usual
     * re-sweep case, and why one costs no writes. Points are named by their **index** in the list
     * handed to [plan], not by [TrackPoint.id]: a backup carries no point ids (its `pointFields`
     * has no such column), so every restored point has id 0 and an id-keyed set would match them
     * all at once; the caller maps an index back to a row id when it has one. Where the clock then
     * sits is [TrackBounds]' to say, off the points this leaves.
     */
    data class Plan(
        /** Each point gaining a flag, or holding one under another reason, with the flag it takes. */
        val ignore: Map<Int, IgnoreReason>,
        val restore: Set<Int>,
        val stays: List<EdgeStayDetector.EdgeStay>,
    ) {
        val movesPoints: Boolean get() = ignore.isNotEmpty() || restore.isNotEmpty()
    }

    /** A track's points and clock as the current rules leave them — see [settle]. */
    data class Settled(
        val plan: Plan,
        /** [points] with [plan] applied: the same instance when it moves nothing. */
        val points: List<TrackPoint>,
        val bounds: TrackBounds.Bounds,
    )

    /**
     * The rules over one track, in the order that makes them one answer: the overrun is planned off
     * the raw recording, the stops inside what remains are thinned ([StationaryRuns]), and the clock
     * is then read from what survives. The order is the whole of it — bounds taken before the flags
     * land would keep the overrun's own fixes — so it is a function rather than a recipe each writer
     * repeats. What a caller then does with the result differs and stays theirs: a stored track has rows to update, a restored one has none yet.
     * [track]'s activity and source pick the overrun's tuning ([EdgeStayDetector.paramsFor]) and
     * whether its stops are thinned, and a rule a track is not under withdraws every flag of its own
     * that the track holds. [endedAt] stands in for the row's end, which an open row lacks.
     */
    fun settle(points: List<TrackPoint>, track: Track, endedAt: Long, distance: DistanceFn): Settled {
        val params = EdgeStayDetector.paramsFor(track.activityType, track.source)
        val plan = plan(points, params, thinsStops(track.source), distance)
        val applied = applied(points, plan)
        return Settled(plan, applied, TrackBounds.of(applied, track.startedAt, endedAt))
    }

    /** A manual track's fixes are typed ends and a router's shape, not a receiver's record. */
    private fun thinsStops(source: String?): Boolean = source != TrackOrigin.MANUAL.code

    /** [points] is *all* of a track's points in order, good and ignored alike. Half of [settle],
     *  which is how a writer runs it — apart, the clock would be read off the wrong points. */
    internal fun plan(
        points: List<TrackPoint>,
        params: EdgeStayDetector.Params?,
        thinsStops: Boolean,
        distance: DistanceFn,
    ): Plan {
        val held = HashMap<Int, IgnoreReason>()
        for ((i, p) in points.withIndex()) {
            if (!p.ignored) continue
            val reason = IgnoreReason.fromCode(p.ignoreReason)
            if (reason != null && !reason.isRejection) held[i] = reason
        }

        // Cleared, not filtered out: the detectors read the recording as it arrived.
        val raw = if (held.isEmpty()) {
            points
        } else {
            points.mapIndexed { i, p -> if (i in held) p.copy(ignored = false, ignoreReason = null) else p }
        }
        val stays = params?.let { EdgeStayDetector.detect(raw, it, distance) }.orEmpty()
        val overrun = raw.indices.filterTo(HashSet()) { i ->
            !raw[i].ignored && stays.any { it.movesOut(raw[i].timestamp) }
        }
        val wanted = overrun.associateWithTo(HashMap<Int, IgnoreReason>()) { IgnoreReason.EDGE_STAY }
        // A stop at a track's edge is the overrun's: thinned first, it would leave the edge rule no
        // dwell to find.
        if (thinsStops) {
            val beyondOverrun = if (overrun.isEmpty()) {
                raw
            } else {
                raw.mapIndexed { i, p -> if (i in overrun) p.copy(ignored = true) else p }
            }
            for (i in StationaryRuns.inner(beyondOverrun, distance)) wanted[i] = IgnoreReason.STATIONARY
        }

        return Plan(
            ignore = wanted.filter { (i, reason) -> held[i] != reason },
            restore = held.keys - wanted.keys,
            stays = stays,
        )
    }

    /** [plan] applied to the points in memory — what the rows will read after the writes, and the
     *  only form available to a backup restore, whose points have no rows yet. The other half of
     *  [settle]. */
    internal fun applied(points: List<TrackPoint>, plan: Plan): List<TrackPoint> =
        if (!plan.movesPoints) {
            points
        } else {
            points.mapIndexed { i, p ->
                val reason = plan.ignore[i]
                when {
                    reason != null -> p.copy(ignored = true, ignoreReason = reason.code)
                    i in plan.restore -> p.copy(ignored = false, ignoreReason = null)
                    else -> p
                }
            }
        }

    /** One track edge the recorder ran on through, as stored. */
    data class Overrun(
        val side: EdgeStayDetector.Side,
        val stayMs: Long,
        /** The flagged fixes plus the good fix they hang off, in time order — a polyline that
         *  meets the drawn track instead of starting a leg short of it. */
        val points: List<TrackPoint>,
    )

    /**
     * The overrun read back off the stored flags, for the track screen: [good] is the track's
     * usable points, [stayPoints] the ones flagged by this rule. Nothing is re-detected — the
     * screen shows what the rows say, so it can't disagree with them.
     */
    fun overruns(good: List<TrackPoint>, stayPoints: List<TrackPoint>): List<Overrun> {
        if (good.isEmpty() || stayPoints.isEmpty()) return emptyList()
        val first = good.first()
        val last = good.last()
        val lead = stayPoints.filter { it.timestamp < first.timestamp }
        val tail = stayPoints.filter { it.timestamp > last.timestamp }
        return buildList {
            if (lead.isNotEmpty()) {
                add(
                    Overrun(
                        EdgeStayDetector.Side.START,
                        first.timestamp - lead.first().timestamp,
                        lead + first,
                    ),
                )
            }
            if (tail.isNotEmpty()) {
                add(
                    Overrun(
                        EdgeStayDetector.Side.END,
                        tail.last().timestamp - last.timestamp,
                        listOf(last) + tail,
                    ),
                )
            }
        }
    }
}
