package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.DISCARDED_RETENTION_DAYS
import io.github.valeronm.breadcrumb.data.db.TrackSummary

/**
 * Hides a [StayDeriver.Stay] on the timeline when [TrackMerge] offers no merge — unlike activity or
 * writer, not stop length — without joining the bracketing tracks. The paths stay as they were; only
 * the row and its visit count go away until the reader undoes it.
 */
object StaySuppress {

    /** Pair identity survives backup import even when settling changes a track's bounds. */
    data class Plan(val afterTrackId: Long, val laterTrackId: Long)

    /** A zero date means permanently hidden, outside the local recovery bin. */
    data class Deleted(val plan: Plan, val deletedAt: Long) {
        fun recoverable(now: Long): Boolean =
            deletedAt > 0 && now - deletedAt < DISCARDED_RETENTION_DAYS * 86_400_000L
    }

    fun visibleIntervals(
        intervals: List<StayDeriver.Interval>,
        successors: Map<Long, Long>,
        hidden: Set<Plan>,
    ): List<StayDeriver.Interval> = intervals.filterNot {
        it is StayDeriver.Stay && successors[it.afterTrackId]?.let { next -> Plan(it.afterTrackId, next) in hidden } == true
    }

    /**
     * A plan to hide the stay after [before], or null when [TrackMerge] already offers a
     * merge or the stay is still open. When [TrackMerge.plan] would refuse (unlike activity or
     * writer, not because the stop is long) the reader may hide the row without joining tracks.
     */
    fun plan(
        before: TrackSummary,
        after: TrackSummary,
        intervalEnd: Long?,
        mergeOffered: Boolean,
    ): Plan? {
        if (mergeOffered || intervalEnd == null) return null
        return Plan(before.id, after.id)
    }

    fun plansByAnchor(
        intervals: List<StayDeriver.Interval>,
        neighbors: Map<Long, TrackMerge.Neighbors>,
        mergePlans: Map<Long, TrackMerge.Plan>,
    ): Map<Long, Plan> = buildMap {
        for (interval in intervals) {
            if (interval !is StayDeriver.Stay) continue
            val anchor = interval.afterTrackId
            val n = neighbors[anchor] ?: continue
            plan(n.before, n.after, interval.end, mergePlans[anchor] != null)
                ?.let { put(anchor, it) }
        }
    }
}
