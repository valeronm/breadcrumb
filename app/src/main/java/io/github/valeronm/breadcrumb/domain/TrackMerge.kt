package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary

/**
 * Decides whether the interval between two tracks can be closed by merging them — the fix for a
 * continuous outing that got split (e.g. a walk broken by a run misdetection whose track was
 * discarded, leaving a 1-min stay between two walk tracks). Stays (endpoints agree) and gaps (they
 * don't — the recorder missed the leg) both qualify; either way the merge marks a segment break at
 * the seam, so the missed leg draws as nothing and counts as no distance. Pure; the UI offers the
 * merge on a swipe and the repository performs it.
 */
object TrackMerge {

    /** An interval this short (or shorter) between same-activity tracks is a candidate for merging. */
    const val MAX_INTERVAL_MS = 5 * 60_000L

    /** Merge the two tracks bracketing the interval into a new track; [earlierId] precedes [laterId]. */
    data class Plan(val earlierId: Long, val laterId: Long)

    /**
     * A plan to merge across the interval between [before] (ends into it) and [after] (starts out
     * of it), or null if the interval is too long, still ongoing, or the tracks aren't the same
     * activity or from the same writer. Refusing across writers is what keeps [TrackSummary.source]
     * true of every row: a merge fuses two tracks' fixes into one, so a recorded track joined to an
     * imported one would leave a row no single source describes — and no "mixed" code could rescue
     * it, since a split of that row would stamp both halves mixed and the rule that fills rows
     * predating the column cannot recognise mixedness at all (a recorded track may hold fixes the
     * platform gave no accuracy radius for, which is what a half-imported one looks like). A stay on
     * a named place is mergeable like any other: the pin is untouched, only
     * that one visit stops counting, and the merge undoes — the stay re-derives from the tracks
     * the moment it does.
     */
    fun plan(
        before: TrackSummary,
        after: TrackSummary,
        intervalStart: Long,
        intervalEnd: Long?,
    ): Plan? {
        if (intervalEnd == null || intervalEnd - intervalStart > MAX_INTERVAL_MS) return null
        if (before.activityType != after.activityType) return null
        if (before.source != after.source) return null
        return Plan(earlierId = before.id, laterId = after.id)
    }

    /**
     * Every mergeable interval's plan, keyed by the track it follows — the handle a timeline row
     * looks its own offer up by, whatever the row's bounds say. That indirection is the point: pass
     * the intervals **as derived**, never the per-day slices the timeline renders — a stop across
     * local midnight slices into pieces, and a merge offered on the two-minute pre-midnight piece
     * of a half-hour stay would fuse the two tracks across the whole stop; judging the interval
     * whole, once, keeps a slice from claiming a duration that isn't its own. [neighbors] holds
     * each anchor's own summary and its chronological successor, so an anchor with nothing after it
     * (the tail, into a track still recording) isn't in the map and yields no plan — one map rather
     * than an id-keyed pair, because two lookups that must agree on what follows what are two
     * chances to disagree.
     */
    fun plansByAnchor(
        intervals: List<StayDeriver.Interval>,
        neighbors: Map<Long, Pair<TrackSummary, TrackSummary>>,
    ): Map<Long, Plan> = buildMap {
        for (interval in intervals) {
            val anchor = interval.afterTrackId
            val (before, after) = neighbors[anchor] ?: continue
            plan(before, after, interval.start, interval.end)?.let { put(anchor, it) }
        }
    }
}
