package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary

/**
 * Decides whether the interval between two tracks can be closed by merging them — the fix for a
 * continuous outing that got split (e.g. a walk broken by a brief run misdetection whose track was
 * then discarded, leaving a 1-min stay between two walk tracks). Both kinds of interval qualify:
 * a *stay*, where the endpoints agree, and a *gap*, where they don't and the recorder simply
 * missed the leg between them. The merge marks a segment break at the seam either way, so the
 * missed leg is drawn as nothing and counts as no distance. Pure; the UI offers the merge on a
 * swipe and the repository performs it.
 */
object TrackMerge {

    /** An interval this short (or shorter) between same-activity tracks is a candidate for merging. */
    const val MAX_INTERVAL_MS = 5 * 60_000L

    /** Merge the two tracks bracketing the interval into a new track; [earlierId] precedes [laterId]. */
    data class Plan(val earlierId: Long, val laterId: Long)

    /**
     * A plan to merge across the interval between [before] (ends into it) and [after] (starts out
     * of it), or null if the interval is too long, still ongoing, or the two tracks aren't the same
     * activity.
     *
     * A stay on a named place is mergeable like any other. The pin is untouched and only that one
     * visit stops counting, which is recoverable in two ways — the merge itself undoes, and the
     * stay re-derives from the tracks the moment it does.
     */
    fun plan(
        before: TrackSummary,
        after: TrackSummary,
        intervalStart: Long,
        intervalEnd: Long?,
    ): Plan? {
        if (intervalEnd == null || intervalEnd - intervalStart > MAX_INTERVAL_MS) return null
        if (before.activityType != after.activityType) return null
        return Plan(earlierId = before.id, laterId = after.id)
    }
}
