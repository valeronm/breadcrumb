package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * How [EdgeStayDetector]'s verdict is recorded: the overrun's fixes are flagged
 * [IgnoreReason.EDGE_STAY] and stay on the track — not bad fixes but perfectly good fixes of a
 * phone that had already arrived, and "ignored" is exactly the status they need, which the app
 * already has: ignored points keep their rows while dropping out of distance, the rendered line,
 * the endpoints, and GPX export. Nothing is moved to another track and nothing is deleted, so the
 * operation is undone by clearing a flag. Two rules make that safe to apply automatically and
 * re-apply forever:
 *  1. **Detection never sees its own output.** [plan] hands the detector the points with the edge
 *     flags cleared, so the overrun is always derived from the raw recording — fed the shortened
 *     track instead, it would find a fresh stay inside the remainder each time and walk the track
 *     backwards, one sweep at a time.
 *  2. **A flag survives only where the rule still re-derives it.** Every flag is reconsidered,
 *     wherever it sits, and one the detector doesn't find again is withdrawn. [TrackMerge] copies
 *     two tracks into one, putting the earlier track's flagged tail mid-track where an edge rule
 *     will never re-derive it, so it is handed back: those fixes are the merged track's own path
 *     through a stop it drove on from, and holding the buried flag because nothing re-examines it
 *     would keep them off the line on the say-so of a rule that stopped applying — a gap no map
 *     layer drew and no legend named.
 * Pure and Android-free.
 */
object EdgeStayIgnore {

    /** Whether [point] carries this rule's flag (as opposed to a quality rejection). */
    fun isEdgeStay(point: TrackPoint): Boolean =
        point.ignored && point.ignoreReason == IgnoreReason.EDGE_STAY.code

    /**
     * What a track's rows should read once the current rule has had its say: which points gain the
     * flag, which lose it, and where the track's clock now starts and ends. Empty index sets and
     * unchanged bounds mean the stored state already agrees — the usual re-sweep case, and why one
     * costs no writes. Points are named by their **index** in the list handed to [plan], not by
     * [TrackPoint.id]: a backup carries no point ids (its `pointFields` has no such column), so
     * every restored point has id 0 and an id-keyed set would match them all at once; the caller
     * maps an index back to a row id when it has one.
     */
    data class Plan(
        val ignore: Set<Int>,
        val restore: Set<Int>,
        /** The track's bounds after the cut — pulled in to a boundary fix, or pushed back out to
         *  the raw recording where a stay was withdrawn. */
        val startedAt: Long,
        val endedAt: Long,
        val stays: List<EdgeStayDetector.EdgeStay>,
    ) {
        val movesPoints: Boolean get() = ignore.isNotEmpty() || restore.isNotEmpty()
    }

    /**
     * [points] is *all* of a track's points in order, good and ignored alike; [startedAt]/[endedAt]
     * the track's current bounds. Each bound comes back as a stay's boundary fix where one was
     * found, else the wider of the stored bound and the outermost fix — which restores the clock
     * when a stay is withdrawn: the raw bound isn't stored anywhere, so the outermost fix stands in
     * for it, differing only by the seconds between the last fix and the recorder noticing, and
     * only ever in that direction.
     */
    fun plan(
        points: List<TrackPoint>,
        startedAt: Long,
        endedAt: Long,
        params: EdgeStayDetector.Params,
        distance: DistanceFn,
    ): Plan {
        val held = points.indices.filterTo(HashSet()) { i -> isEdgeStay(points[i]) }

        // Cleared, not filtered out: the detector reads the recording as it arrived.
        val raw = points.mapIndexed { i, p ->
            if (i in held) p.copy(ignored = false, ignoreReason = null) else p
        }
        val stays = EdgeStayDetector.detect(raw, params, distance)
        val wanted = raw.indices.filterTo(HashSet()) { i ->
            !raw[i].ignored && stays.any { it.movesOut(raw[i].timestamp) }
        }

        fun boundary(side: EdgeStayDetector.Side) =
            stays.firstOrNull { it.side == side }?.boundaryTs
        return Plan(
            ignore = wanted - held,
            restore = held - wanted,
            startedAt = boundary(EdgeStayDetector.Side.START)
                ?: minOf(startedAt, points.firstOrNull()?.timestamp ?: startedAt),
            endedAt = boundary(EdgeStayDetector.Side.END)
                ?: maxOf(endedAt, points.lastOrNull()?.timestamp ?: endedAt),
            stays = stays,
        )
    }

    /** [plan] applied to the points in memory — what the rows will read after the writes, and the
     *  only form available to a backup restore, whose points have no rows yet. */
    fun applied(points: List<TrackPoint>, plan: Plan): List<TrackPoint> =
        if (!plan.movesPoints) {
            points
        } else {
            points.mapIndexed { i, p ->
                when (i) {
                    in plan.ignore -> p.copy(ignored = true, ignoreReason = IgnoreReason.EDGE_STAY.code)
                    in plan.restore -> p.copy(ignored = false, ignoreReason = null)
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
