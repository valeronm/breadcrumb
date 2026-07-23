package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.DistanceFn
import io.github.valeronm.breadcrumb.data.IgnoreReason
import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * How [EdgeStayDetector]'s verdict is recorded: the overrun's fixes are flagged
 * [IgnoreReason.EDGE_STAY] and stay on the track. They are not bad fixes — they are perfectly good
 * fixes of a phone that had already arrived — but "ignored" is exactly the status they need, and
 * the app already has it: ignored points keep their rows while dropping out of distance, the
 * rendered line, the endpoints, and GPX export. Nothing is moved to another track and nothing is
 * deleted, so the operation is undone by clearing a flag.
 *
 * Two rules make that safe to apply automatically and re-apply forever:
 *
 *  1. **Detection never sees its own output.** [plan] hands the detector the points with the edge
 *     flags cleared, so the overrun is always derived from the raw recording. Feeding it the
 *     already-shortened track instead would let it find a fresh stay inside the remainder each
 *     time and walk the track backwards, one sweep at a time.
 *  2. **Only the edges are this rule's to move.** A flag is a candidate for clearing only when it
 *     sits outside the first/last *good* fix. [TrackMerge] copies two tracks into one, which puts
 *     the earlier track's flagged tail in the middle of the result — a stop the merged track
 *     genuinely drove through, and not something an edge detector will ever re-derive.
 *
 * Pure and Android-free.
 */
object EdgeStayIgnore {

    /** Whether [point] carries this rule's flag (as opposed to a quality rejection). */
    fun isEdgeStay(point: TrackPoint): Boolean =
        point.ignored && point.ignoreReason == IgnoreReason.EDGE_STAY.code

    /**
     * What a track's rows should look like once the current rule has had its say: which points
     * gain the flag, which lose it, and where the track's clock now starts and ends. Empty index
     * sets and unchanged bounds mean the stored state already agrees — the usual case on a
     * re-sweep, and the reason one costs no writes.
     *
     * Points are named by their **index** in the list handed to [plan], not by
     * [TrackPoint.id]: a backup carries no point ids (the format's `pointFields` has no such
     * column), so every point a restore parses has id 0 and an id-keyed set would match all of
     * them at once. An index is meaningful whatever the points came from; the caller maps it back
     * to a row id when it has one.
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
     * are the track's current bounds.
     *
     * The bounds come back as the tighter of the two readings at each end: a stay's boundary fix
     * where one was found, and otherwise the wider of the stored bound and the outermost fix —
     * which is what restores the clock when a stay is withdrawn. The raw bound isn't stored
     * anywhere, so the outermost fix is what stands in for it; the two differ by the seconds
     * between the last fix and the recorder noticing, and only ever in that direction.
     */
    fun plan(
        points: List<TrackPoint>,
        startedAt: Long,
        endedAt: Long,
        params: EdgeStayDetector.Params,
        distance: DistanceFn,
    ): Plan {
        val firstGood = points.indexOfFirst { !it.ignored }
        val lastGood = points.indexOfLast { !it.ignored }
        val held = points.indices.filterTo(HashSet()) { i ->
            isEdgeStay(points[i]) && (firstGood < 0 || i < firstGood || i > lastGood)
        }

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
