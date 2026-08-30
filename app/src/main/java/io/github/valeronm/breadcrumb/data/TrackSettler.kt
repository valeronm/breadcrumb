package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.IDS_PER_STATEMENT
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackDao
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.EdgeStayDetector
import io.github.valeronm.breadcrumb.domain.EdgeStayIgnore
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.util.DebugLog

private const val TAG = "Breadcrumb"

/**
 * What a track whose points changed owes its row: the recorder's overrun settled off the path and
 * the clock onto the fixes that survive ([EdgeStayIgnore.settle]), then the aggregates those points
 * add up to. Every writer of a stored track's points enters through [settleAndRefresh], and a
 * track with no row yet through [settleForInsert]; what stays with the writer is only whether it
 * owes the derivation a repair. Runs inside the caller's transaction throughout.
 */
internal class TrackSettler(private val dao: TrackDao) {

    /** A track as [settleTrack] left it: the domain's verdict, plus what of it the row didn't
     *  already say. */
    class Applied(
        val settled: EdgeStayIgnore.Settled,
        /** Whether the rules moved anything — a flag or either bound. False on the re-runs that
         *  agree with the stored rows, which is what lets a re-sweep cost no writes. */
        val changed: Boolean,
    ) {
        val bounds get() = settled.bounds

        /** Whether a *point* changed hands. The aggregates are a function of the points alone, so
         *  recomputing them when this is false writes back what it read. */
        val pointsMoved get() = settled.plan.movesPoints

        /** The aggregates the settled points add up to — one walk, taken only when asked for, which
         *  is what keeps a sweep that moved nothing from walking every track's points. */
        val stats by lazy(LazyThreadSafetyMode.NONE) { TrackStats.of(settled.points) }
    }

    /**
     * [settleTrack], then the aggregates the surviving points add up to, stored where they could
     * have moved — always where the row's totals cannot be trusted ([totalsStale]), otherwise only
     * where the settle itself moved a point. The derivation is deliberately left to the caller,
     * whose own write may have moved the timeline as much as the settle did; [Applied.changed] says
     * whether the settle did.
     */
    suspend fun settleAndRefresh(
        track: Track,
        endedAt: Long,
        points: List<TrackPoint>,
        totalsStale: Boolean,
    ): Applied {
        val applied = settleTrack(track, endedAt, points)
        if (totalsStale || applied.pointsMoved) refreshStats(track.id, applied.stats)
        return applied
    }

    /**
     * Store a track's aggregates on its row — the only writer of the denormalized columns, so every
     * path that changes a track's points must end here or the timeline shows stale counts. [stats]
     * is walked from *all* of the track's points, ignored ones included, by the caller: every path
     * that ends here has just walked or rewritten them, and none may re-read.
     */
    suspend fun refreshStats(trackId: Long, stats: TrackStats.Stats) {
        dao.updateStats(stats.toUpdate(trackId))
    }

    /** [refreshStats] for a row with nothing to settle — still open, or not yet inserted — walked
     *  from [points], which are all of the track's. */
    suspend fun refreshStats(trackId: Long, points: List<TrackPoint>) =
        refreshStats(trackId, TrackStats.of(points))

    /**
     * The settle for a track that has no row yet: the verdict [settleTrack] would write, handed
     * back for the caller to insert with. A track carrying no end — a file's, never one the app
     * finished — settles over an empty window, which moves nothing.
     */
    fun settleForInsert(track: Track, points: List<TrackPoint>): EdgeStayIgnore.Settled =
        settleOf(track, track.endedAt ?: track.startedAt, points)

    /** Whether a retype from [from] to [to] changes the tuning the overrun rule runs under. */
    fun tuningChanges(from: String, to: ActivityType): Boolean =
        EdgeStayDetector.paramsFor(to.name) != EdgeStayDetector.paramsFor(from)

    /** The rules over a track's points — the one place that says which tuning and which distance
     *  they run under. */
    private fun settleOf(track: Track, endedAt: Long, points: List<TrackPoint>): EdgeStayIgnore.Settled =
        EdgeStayIgnore.settle(
            points = points,
            startedAt = track.startedAt,
            endedAt = endedAt,
            params = EdgeStayDetector.paramsFor(track.activityType),
            distance = AndroidDistance,
        )

    /**
     * Hand back the jump-flagged fixes the retyped activity's ceiling accepts ([TrackQuality.jumpRestores]),
     * and return the points as their rows now read — or null when the ceiling accepted none, which
     * is how the caller knows nothing moved.
     */
    suspend fun restoreJumps(
        trackId: Long,
        points: List<TrackPoint>,
        activityType: ActivityType,
    ): List<TrackPoint>? {
        val restores = TrackQuality.jumpRestores(points, activityType, AndroidDistance)
        if (restores.isEmpty()) return null
        restores.map { points[it].id }.chunked(IDS_PER_STATEMENT).forEach { dao.clearIgnored(it) }
        DebugLog.i(
            TAG,
            "track $trackId: ${restores.size} jump fixes restored under the " +
                "${activityType.name.lowercase()} ceiling",
        )
        return points.mapIndexed { i, p ->
            if (i in restores) p.copy(ignored = false, ignoreReason = null) else p
        }
    }

    /**
     * Write what [EdgeStayIgnore.settle] says a stored track's points and clock should read — the
     * recorder's overrun off the path and the bounds on the fixes that survive. Nothing is destroyed
     * — the points stay, and a rule that later withdraws a stay hands them straight back, the clock
     * reopening onto them — and it is idempotent, which lets every path that changes a track's
     * points end here. The point flags and both bounds are written, except on a still-open row
     * ([endedAt] is then the proposed end time), where the caller is mid-finish and writes the end
     * itself.
     */
    private suspend fun settleTrack(track: Track, endedAt: Long, points: List<TrackPoint>): Applied {
        val settled = settleOf(track, endedAt, points)
        val plan = settled.plan
        // The plan names points by position; these ones came out of the database, so each has a
        // row id to write against.
        plan.ignore.map { points[it].id }.chunked(IDS_PER_STATEMENT)
            .forEach { dao.setIgnored(it, IgnoreReason.EDGE_STAY.code) }
        plan.restore.map { points[it].id }.chunked(IDS_PER_STATEMENT).forEach { dao.clearIgnored(it) }
        val movedStart = settled.bounds.startedAt != track.startedAt
        val movedEnd = settled.bounds.endedAt != endedAt
        if (movedStart) dao.setStartedAt(track.id, settled.bounds.startedAt)
        // An open row's end is the caller's to write; [endedAt] was only its proposal.
        if (movedEnd && track.endedAt != null) dao.closeTrack(track.id, settled.bounds.endedAt)
        if (plan.movesPoints) {
            val what = plan.stays
                .joinToString { "${it.side.name.lowercase()} overrun of ${it.stayMs / 1000}s" }
                .ifEmpty { "no overrun" }
            DebugLog.i(
                TAG,
                "track ${track.id}: $what " +
                    "(${plan.ignore.size} points ignored, ${plan.restore.size} restored)",
            )
        }
        return Applied(settled, changed = plan.movesPoints || movedStart || movedEnd)
    }
}
